-- Planned maintenance starts as SCHEDULED rather than INVESTIGATING, which describes an unplanned fault.
ALTER TABLE outage_incidents DROP CONSTRAINT outage_incidents_status_check;
ALTER TABLE outage_incidents ADD CONSTRAINT outage_incidents_status_check
    CHECK (status IN ('SCHEDULED','INVESTIGATING','CREW_DISPATCHED','RESTORING','RESOLVED'));

-- Planned outages that never progressed past their initial status were scheduled, not under investigation.
UPDATE outage_incidents SET status = 'SCHEDULED', updated_at = now()
WHERE severity = 'PLANNED' AND status = 'INVESTIGATING';
