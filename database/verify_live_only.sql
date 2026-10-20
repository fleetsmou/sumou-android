BEGIN;
DO $test$
DECLARE
  d uuid; other_driver uuid; tok uuid := gen_random_uuid();
  s jsonb; sid text; initial_count bigint; after_count bigint; code text; payload jsonb;
  got public.shifts%rowtype; blocked boolean := false;
BEGIN
  select count(*) into initial_count from public.shift_points;
  insert into public.drivers(name,pin,track_code) values('SUMOU transactional tracking check','unused',null) returning id into d;
  insert into public.driver_sessions(token,driver_id) values(tok,d::text);
  insert into public.drivers(name,pin,track_code) values('SUMOU transactional ownership check','unused',null) returning id into other_driver;
  payload := jsonb_build_object('platform','Talabat','start_odo',100,'lat',29.30000,'lng',47.90000,'acc',10,'src','gps','at',clock_timestamp()-interval '10 seconds','time','test');
  execute 'set local role anon';
  s := public.driver_start_shift(tok,payload);
  sid := s->>'id';
  execute 'reset role';
  if s->>'live_pos_at' is null or (s->>'live_acc')::real<>10 then raise exception 'Clock-in position not seeded'; end if;
  execute 'set local role anon';
  perform public.driver_update_live(tok,sid,jsonb_build_object('lat',29.30010,'lng',47.90010,'acc',10,'at',clock_timestamp(),'speed',15),true);
  perform public.driver_add_points(tok,sid,'[{"lat":29.3,"lng":47.9,"acc":10}]'::jsonb);
  perform public.driver_add_points_v2(tok,sid,'[{"lat":29.3,"lng":47.9,"acc":10,"client_point_id":"legacy-test"}]'::jsonb);
  execute 'reset role';
  select * into got from public.shifts where id::text=sid;
  if got.live_lat<>29.30010 or got.status<>'ACTIVE' then raise exception 'Latest live update failed'; end if;
  insert into public.shift_points(shift_id,driver_id,lat,lng,acc) values(sid,d::text,29.3,47.9,10);
  select count(*) into after_count from public.shift_points;
  if after_count<>initial_count then raise exception 'Route points were saved'; end if;
  -- A delayed older fix must not replace the current position.
  perform public.driver_update_live(tok,sid,jsonb_build_object('lat',29.30005,'lng',47.90005,'acc',10,'at',clock_timestamp()-interval '1 minute'),true);
  select * into got from public.shifts where id::text=sid;
  if got.live_lat<>29.30010 then raise exception 'Stale fix replaced live position'; end if;
  insert into public.shifts(driver_id,status) values(other_driver,'ACTIVE') returning id::text into code;
  execute 'set local role anon';
  begin
    perform public.driver_update_live(tok,code,jsonb_build_object('lat',29.3,'lng',47.9),true);
  exception when others then
    if SQLERRM='NO_ACTIVE_SHIFT' then blocked:=true; else raise; end if;
  end;
  if not blocked then raise exception 'Ownership check failed'; end if;
  perform public.driver_end_shift(tok,sid,jsonb_build_object('end_odo',101,'lat',29.3001,'lng',47.9001));
  blocked := false;
  begin
    perform public.driver_update_live(tok,sid,jsonb_build_object('lat',29.3002,'lng',47.9002,'acc',10,'at',clock_timestamp()),false);
  exception when others then
    if SQLERRM='NO_ACTIVE_SHIFT' then blocked:=true; else raise; end if;
  end;
  if not blocked then raise exception 'Post-clock-out update was accepted'; end if;
  execute 'reset role';
  select count(*) into after_count from public.shift_points;
  if after_count<>initial_count then raise exception 'History count changed'; end if;
END
$test$;
ROLLBACK;
SELECT 'PASS: clock-in seed, latest update with legacy history flag, old batch RPCs, direct history guard, stale rejection, ownership, clock-out rejection; all fixtures rolled back' AS verification;
