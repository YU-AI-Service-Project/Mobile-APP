-- Apply once in an empty, migrated integration-test schema.
START TRANSACTION;
SET @observed = UTC_TIMESTAMP(3);
INSERT INTO charging_stations (station_id, source_station_id, station_name, address, latitude, longitude, parking_free_code)
VALUES ('KECO:DEMOLOCAL', 'DEMOLOCAL', 'Integration test station', 'Backend integration fixture', 35.8714, 128.6014, 'Y');
INSERT INTO chargers (station_id, source_charger_id, charger_type_code, connector_type_label, output_kw)
VALUES ('KECO:DEMOLOCAL', '01', '04', 'DC_COMBO', 50);
SET @charger = LAST_INSERT_ID();
INSERT INTO collection_runs (run_type, status, started_at, completed_at, is_complete)
VALUES ('STATUS_FULL', 'SUCCEEDED', @observed, @observed, true);
SET @run = LAST_INSERT_ID();
INSERT INTO charger_status_observations (charger_id, collection_run_id, raw_status_code, normalized_status, observed_at, source_event_fingerprint)
VALUES (@charger, @run, '2', 'AVAILABLE', @observed, REPEAT('a',64));
INSERT INTO charger_current_status (charger_id, latest_observation_id, raw_status_code, normalized_status, last_observed_at)
VALUES (@charger, LAST_INSERT_ID(), '2', 'AVAILABLE', @observed);
COMMIT;
