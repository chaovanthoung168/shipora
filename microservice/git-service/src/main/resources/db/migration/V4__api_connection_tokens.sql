-- Keep V3 intact for databases that already applied the browser-flow migration.
ALTER TABLE git.connection_states RENAME COLUMN browser_hash TO connection_token_hash;
ALTER TABLE git.browser_connection_choices RENAME TO connection_choices;
ALTER TABLE git.connection_choices RENAME COLUMN browser_hash TO connection_token_hash;
ALTER INDEX git.ix_browser_choices_session RENAME TO ix_connection_choices_client;
ALTER INDEX git.ix_browser_choices_expiry RENAME TO ix_connection_choices_expiry;
