Bundled libraries for the driver APK (avoids mandatory external CDN scripts at launch):

Leaflet 1.9.4
https://cdn.jsdelivr.net/npm/leaflet@1.9.4/dist/leaflet.js
https://cdn.jsdelivr.net/npm/leaflet@1.9.4/dist/leaflet.css
Images from the same dist/images directory. BSD 2-Clause license in leaflet/LICENSE.

Supabase JavaScript 2.118.0
https://cdn.jsdelivr.net/npm/@supabase/supabase-js@2.118.0/dist/umd/supabase.js
MIT license in supabase/LICENSE.
Snapshot of the existing driver's @supabase/supabase-js@2 CDN dependency on 2026-10-20.

Only the bundled www/index.html references change to local vendor paths.
Map tiles and online ERP APIs still require internet.
