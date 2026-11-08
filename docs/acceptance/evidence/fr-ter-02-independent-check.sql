-- Independent recomputation of zoning discrepancies for Бишкек (set-based SQL, not the Java loop)
WITH z AS (SELECT f.* FROM spatial.features f JOIN catalog.layers l ON l.id=f.layer_id AND l.code='urban_zones' AND f.version=l.published_version AND NOT f.deleted),
     d AS (SELECT f.* FROM spatial.features f JOIN catalog.layers l ON l.id=f.layer_id AND l.code='current_development' AND f.version=l.published_version AND NOT f.deleted),
     t AS (SELECT f.geom FROM spatial.features f JOIN catalog.layers l ON l.id=f.layer_id AND l.code='atd_oblasts' WHERE f.title='Бишкек'),
     dev AS (SELECT d.id, d.title, d.attrs->>'use_type' use_type, ST_Area(d.geom::geography) area, d.geom FROM d, t WHERE ST_Intersects(d.geom, t.geom)),
     ov AS (SELECT dev.id, z.attrs->>'zone_type' zt, ST_Area(ST_Intersection(dev.geom, z.geom)::geography) a FROM dev JOIN z ON ST_Intersects(dev.geom, z.geom)),
     agg AS (SELECT dev.id, dev.title, dev.use_type, dev.area, coalesce(sum(ov.a),0) covered,
                    (array_agg(ov.zt ORDER BY ov.a DESC))[1] main_zt FROM dev LEFT JOIN ov ON ov.id=dev.id GROUP BY 1,2,3,4),
     cls AS (SELECT a.id, a.title, 'NO_ZONE' type FROM agg a WHERE a.covered/a.area < 0.05
       UNION ALL SELECT a.id, a.title, 'CROSSES_ZONES' FROM agg a WHERE a.covered/a.area >= 0.05
             AND EXISTS (SELECT 1 FROM ov WHERE ov.id=a.id AND ov.zt<>a.main_zt AND ov.a/a.area >= 0.10)
       UNION ALL SELECT a.id, a.title, 'USE_NOT_ALLOWED' FROM agg a WHERE a.covered/a.area >= 0.05
             AND EXISTS (SELECT 1 FROM zoning.rules r WHERE r.zone_type=a.main_zt AND r.use_type=a.use_type AND NOT r.allowed)),
     app AS (SELECT dev_feature_id id, type FROM zoning.discrepancies WHERE still_present)
SELECT (SELECT count(*) FROM dev) dev_objects,
       (SELECT count(*) FROM cls) independent, (SELECT count(*) FROM app) app,
       (SELECT string_agg(type||'='||n, ', ' ORDER BY type) FROM (SELECT type, count(*) n FROM cls GROUP BY type) x) independent_by_type,
       (SELECT count(*) FROM cls JOIN app USING (id, type)) matched,
       (SELECT count(*) FROM cls WHERE (id,type) NOT IN (SELECT id,type FROM app)) missed_by_app,
       (SELECT count(*) FROM app WHERE (id,type) NOT IN (SELECT id,type FROM cls)) extra_in_app;
