-- =====================================================================
-- Add PLAYING / PLAYBACK_COMPLETED to call_session_status (Phase VB-1)
-- Forward-only; V1-V33 remain untouched.
-- =====================================================================
-- The PLAYFILE lifecycle (ANSWERED -> PLAYING -> PLAYBACK_COMPLETED ->
-- hangup) needs observable playback states on the call session.
-- CallSessionStatus already models the universal voice call lifecycle;
-- these are the smallest additions (no new duplicate state concept such
-- as PlaybackState/MediaState). PLAYBACK_COMPLETED (playback done,
-- teardown in flight) must be distinct from ANSWERED so a stray
-- PLAYBACK_START can never restart a finished playback.

ALTER TYPE call_session_status ADD VALUE IF NOT EXISTS 'PLAYING';
ALTER TYPE call_session_status ADD VALUE IF NOT EXISTS 'PLAYBACK_COMPLETED';
