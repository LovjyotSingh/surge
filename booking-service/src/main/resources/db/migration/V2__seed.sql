INSERT INTO event_ref (id, title, venue_name, sale_opens_at, zone_id, status) VALUES
 (1, 'Neon Nights Live — Flash Sale', 'Jawaharlal Nehru Stadium, Delhi', '2026-01-01 00:00:00', 'Asia/Kolkata', 'ACTIVE'),
 (2, 'Desert Beats', 'Coca-Cola Arena, Dubai', '2026-01-01 00:00:00', 'Asia/Dubai', 'ACTIVE'),
 (3, 'Marina Jazz Night', 'Esplanade Concert Hall, Singapore', '2026-01-01 00:00:00', 'Asia/Singapore', 'ACTIVE');

-- Event 1: rows A-T x 1-25 (A-D PLATINUM, E-L GOLD, M-T SILVER)
INSERT INTO seat (event_id, section, row_label, number, tier, status, version)
WITH RECURSIVE rws (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM rws WHERE n < 20),
               nums (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM nums WHERE n < 25)
SELECT 1, 'MAIN', CHAR(64 + rws.n), nums.n,
       CASE WHEN rws.n <= 4 THEN 'PLATINUM' WHEN rws.n <= 12 THEN 'GOLD' ELSE 'SILVER' END,
       'AVAILABLE', 0
FROM rws CROSS JOIN nums;

-- Events 2 and 3: rows A-D x 1-25 (A PLATINUM, B GOLD, C-D SILVER)
INSERT INTO seat (event_id, section, row_label, number, tier, status, version)
WITH RECURSIVE rws (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM rws WHERE n < 4),
               nums (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM nums WHERE n < 25),
               evs (id) AS (SELECT 2 UNION ALL SELECT 3)
SELECT evs.id, 'MAIN', CHAR(64 + rws.n), nums.n,
       CASE WHEN rws.n = 1 THEN 'PLATINUM' WHEN rws.n = 2 THEN 'GOLD' ELSE 'SILVER' END,
       'AVAILABLE', 0
FROM evs CROSS JOIN rws CROSS JOIN nums;
