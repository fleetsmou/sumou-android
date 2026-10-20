-- Sumou Fleet live-only tracking. No driver records or shifts are deleted.
CREATE OR REPLACE FUNCTION public._apply_live(p_shift uuid, p_driver text, p jsonb, p_point boolean, p_src text DEFAULT 'app'::text)
 RETURNS text
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  r public.shifts%rowtype;
  v_now timestamptz;
  v_at timestamptz;
  v_lat double precision := (p->>'lat')::double precision;
  v_lng double precision := (p->>'lng')::double precision;
  v_acc real := coalesce((p->>'acc')::real, 999);
  v_speed integer := greatest(0,least(250,coalesce((p->>'speed')::numeric,0)))::integer;
  v_ok boolean; v_jump boolean := false; v_due boolean; v_trk_due boolean;
  v_dt double precision; v_dist double precision; v_oacc real;
begin
  select * into r from public.shifts where id=p_shift and driver_id::text=p_driver and status='ACTIVE' for update;
  if not found then return 'NO_ACTIVE_SHIFT'; end if;
  v_now := clock_timestamp();
  v_at := least(coalesce(nullif(p->>'at','')::timestamptz,v_now),v_now);
  v_due := r.live_at is null or v_now-r.live_at >= interval '30 seconds';
  v_trk_due := p_src='traccar' and (r.trk_at is null or v_now-r.trk_at>=interval '30 seconds');
  -- Buffered/repeated fixes prove contact, but cannot make old coordinates current
  -- and do not count as fresh tracker GPS either.
  if (r.live_pos_at is not null and v_at <= r.live_pos_at) or
     (r.started_at is not null and v_at < r.started_at) or v_at < v_now-interval '5 minutes' then
    if v_due then
      update public.shifts set live_at=v_now where id=r.id;
    end if;
    return 'STALE';
  end if;
  v_ok := v_lat is not null and v_lng is not null and v_lat between -90 and 90 and v_lng between -180 and 180
          and not(abs(v_lat)<0.001 and abs(v_lng)<0.001) and v_acc >= 0 and v_acc < 100000;
  if v_ok and r.live_lat is not null and r.live_acc is not null then
    v_dt := greatest(1,extract(epoch from v_at-coalesce(r.live_pos_at,r.started_at,v_at)));
    v_oacc := r.live_acc;
    v_dist := public._gps_dist(r.live_lat,r.live_lng,v_lat,v_lng);
    if v_acc>100 and v_oacc<=100 and v_dt<120 then v_ok:=false; end if;
    if v_ok and v_dt<60 and v_dist>2*(v_acc+v_oacc) and v_dist/v_dt*3.6>180 then
      v_jump:=true;
      if not(v_acc<=100 and r.live_rej_at is not null and v_now-r.live_rej_at<interval '45 seconds'
        and public._gps_dist(r.live_rej_lat,r.live_rej_lng,v_lat,v_lng)
          <= greatest(2*(v_acc+50),extract(epoch from v_now-r.live_rej_at)*50)) then
        v_ok:=false;
      end if;
    end if;
  end if;
  if v_ok then
    if not v_due and v_dist is not null and v_dist<3 and v_speed<=2 and coalesce(r.speed,0)<=2
      and v_acc>=coalesce(r.live_acc,v_acc)*0.75
      and r.live_pos_at >= v_now-interval '30 seconds' and r.live_rej_at is null then
      if v_trk_due then update public.shifts set trk_at=v_now,live_at=v_now where id=r.id; end if;
      return 'UNCHANGED';
    end if;
    update public.shifts set live_lat=v_lat,live_lng=v_lng,live_acc=v_acc,live_pos_at=v_at,
      speed=v_speed,heading=(p->>'heading')::real,live_time=p->>'time',live_at=v_now,live_src=p_src,
      trk_at=case when p_src='traccar' then v_now else trk_at end,
      live_rej_lat=null,live_rej_lng=null,live_rej_at=null where id=r.id;
    -- Live-only fleet: overwrite this shift's latest position; never append route points.
    return 'OK';
  end if;
  if v_due or v_trk_due or v_jump then
    update public.shifts set live_at=v_now,
      trk_at=case when p_src='traccar' then v_now else trk_at end,
      live_rej_lat=case when v_jump then v_lat else live_rej_lat end,
      live_rej_lng=case when v_jump then v_lng else live_rej_lng end,
      live_rej_at=case when v_jump then v_now else live_rej_at end where id=r.id;
  end if;
  return 'SKIPPED';
end $function$
;
CREATE OR REPLACE FUNCTION public.driver_update_live(p_token uuid, p_shift text, p jsonb, p_point boolean DEFAULT false)
 RETURNS void
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare v text := _driver_from_token(p_token); sid uuid;
begin
  select id into sid from shifts where id::text = p_shift and driver_id::text = v and status = 'ACTIVE';
  if sid is null then raise exception 'NO_ACTIVE_SHIFT'; end if;
  perform _apply_live(sid, v, p, false, 'app');
end $function$
;
CREATE OR REPLACE FUNCTION public.driver_start_shift(p_token uuid, p jsonb)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare v text := _driver_from_token(p_token); r shifts%rowtype;
begin
  if exists (select 1 from shifts where driver_id::text = v and status = 'ACTIVE') then raise exception 'ALREADY_ON_DUTY'; end if;
  if coalesce((p->>'start_odo')::numeric, 0) <= 0 then raise exception 'BAD_ODO'; end if;
  begin
    insert into shifts (driver_id, platform, date, status, start_odo, start_odo_photo,
                        login_time, login_place, login_addr, login_lat, login_lng, login_src,
                        live_lat, live_lng, live_time, live_at, started_at, live_pos_at, live_acc, live_src)
    values ((select id from drivers where id::text = v), p->>'platform', kw_today(), 'ACTIVE',
            (p->>'start_odo')::numeric, p->>'photo',
            p->>'time', p->>'place', p->>'addr', (p->>'lat')::double precision, (p->>'lng')::double precision, coalesce(p->>'src','gps'),
            (p->>'lat')::double precision, (p->>'lng')::double precision, p->>'time', now(), now(),
            case when coalesce(p->>'src','gps')='gps' then least(coalesce(nullif(p->>'at','')::timestamptz,now()),now()) else null end,
            (p->>'acc')::real, coalesce(p->>'src','gps'))
    returning * into r;
  exception when unique_violation then
    raise exception 'ALREADY_ON_DUTY';
  end;
  return to_jsonb(r);
end $function$
;
CREATE OR REPLACE FUNCTION public.traccar_ingest(p_code text, p_fixes jsonb)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  d public.drivers%rowtype; s public.shifts%rowtype; f jsonb; last_fix jsonb;
  n integer:=0; added integer; res text; v_at timestamptz;
begin
  select * into d from public.drivers where track_code=upper(trim(p_code));
  if not found then return jsonb_build_object('status','UNKNOWN_DEVICE'); end if;
  select * into s from public.shifts where driver_id=d.id and status='ACTIVE' order by started_at desc limit 1 for update;
  if not found then return jsonb_build_object('status','OFF_DUTY'); end if;
  if jsonb_typeof(p_fixes) is distinct from 'array' or jsonb_array_length(p_fixes)>200 then
    raise exception 'BAD_POINTS';
  end if;
  for f in select e from jsonb_array_elements(p_fixes) e order by (e->>'at')::timestamptz loop
    if nullif(f->>'at','') is null then continue; end if;
    v_at := least((f->>'at')::timestamptz,clock_timestamp());
    -- Do not attach an earlier shift's buffered route to the current shift.
    if v_at is null or v_at<coalesce(s.started_at,s.created_at) then continue; end if;
    if (f->>'lat')::double precision not between -90 and 90 or
       (f->>'lng')::double precision not between -180 and 180 or
       (f->>'lat') is null or (f->>'lng') is null or
       (abs((f->>'lat')::double precision)<0.001 and abs((f->>'lng')::double precision)<0.001) then continue; end if;
    last_fix:=f || jsonb_build_object('at',v_at);
    -- Buffered route history is intentionally discarded.
  end loop;
  if last_fix is null then return jsonb_build_object('status','EMPTY','buffered',n); end if;
  -- _apply_live updates trk_at and live coordinates together, once rather than twice.
  res:=public._apply_live(s.id,d.id::text,last_fix,false,'traccar');
  return jsonb_build_object('status',res,'buffered',n);
end $function$
;
CREATE OR REPLACE FUNCTION public.driver_add_points(p_token uuid, p_shift text, p_pts jsonb)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $function$
declare v text := public._driver_from_token(p_token);
begin
  if not exists(select 1 from public.shifts where id::text=p_shift and driver_id::text=v) then raise exception 'NO_ACTIVE_SHIFT'; end if;
  -- Compatibility for older APKs: acknowledge and discard every route batch.
  -- Authentication and ownership checks remain enforced.
end $function$;

CREATE OR REPLACE FUNCTION public.driver_add_points_v2(p_token uuid, p_shift text, p_pts jsonb)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $function$
declare v text := public._driver_from_token(p_token);
begin
  if not exists(select 1 from public.shifts where id::text=p_shift and driver_id::text=v) then raise exception 'NO_ACTIVE_SHIFT'; end if;
  -- Compatibility for older APKs: acknowledge and discard every route batch.
  -- Authentication and ownership checks remain enforced.
end $function$;

CREATE OR REPLACE FUNCTION public.sumou_discard_route_history()
RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path = '' AS $function$
begin
  -- Defense in depth for legacy clients/direct inserts. Keep callers compatible.
  return null;
end $function$;
REVOKE ALL ON FUNCTION public.sumou_discard_route_history() FROM PUBLIC, anon, authenticated;
CREATE OR REPLACE TRIGGER sumou_live_only_no_route_history
BEFORE INSERT OR UPDATE ON public.shift_points
FOR EACH ROW EXECUTE FUNCTION public.sumou_discard_route_history();
COMMENT ON TRIGGER sumou_live_only_no_route_history ON public.shift_points IS 'Live-only fleet: route history writes are discarded, including legacy APKs.';

