-- Add configurable office geofencing settings and recorded check-in/out location distance
ALTER TABLE attendance_settings ADD COLUMN geofence_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE attendance_settings ADD COLUMN geofence_latitude DOUBLE PRECISION;
ALTER TABLE attendance_settings ADD COLUMN geofence_longitude DOUBLE PRECISION;
ALTER TABLE attendance_settings ADD COLUMN geofence_radius_meters INTEGER NOT NULL DEFAULT 50;

ALTER TABLE attendance_records ADD COLUMN check_in_latitude DOUBLE PRECISION;
ALTER TABLE attendance_records ADD COLUMN check_in_longitude DOUBLE PRECISION;
ALTER TABLE attendance_records ADD COLUMN check_in_distance_meters DOUBLE PRECISION;
ALTER TABLE attendance_records ADD COLUMN check_out_latitude DOUBLE PRECISION;
ALTER TABLE attendance_records ADD COLUMN check_out_longitude DOUBLE PRECISION;
ALTER TABLE attendance_records ADD COLUMN check_out_distance_meters DOUBLE PRECISION;
