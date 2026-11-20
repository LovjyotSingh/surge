-- One database per service (plan Global Constraints). Run once by the mysql image on an empty data directory;
-- 02-users.sh then creates each service's own user, limited to its own database (SEC-05).
CREATE DATABASE IF NOT EXISTS auth;
CREATE DATABASE IF NOT EXISTS booking;
CREATE DATABASE IF NOT EXISTS catalog;
CREATE DATABASE IF NOT EXISTS notification;
