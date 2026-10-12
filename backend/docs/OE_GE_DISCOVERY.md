# OE Ground Executive recommendations and discovery

Both modes use `POST /api/v1/operations/visit-sessions/{id}/oe-recommendations` with a JSON body such as
`{"expectedSessionVersion": 3}`. Every request checks the current OE grant, Session ownership,
City and Team scope, DRAFT state, and Session version before returning staff information.

## Intelligent recommendations

Omit `all`, or use `all=false`. The response has `mode: "RECOMMENDATIONS"` and at most three
eligible candidates, ranked by the existing scheduling evaluator. `rank` starts at 1. The
`RANKED_WITHIN_FIRST_N_CITY_GE_ID_CANDIDATES` diagnostic states the bounded candidate pool used;
these are not asserted to be the best three among every GE in the City. Existing feasibility,
travel-confidence, location, workload, coverage and reservation reasons are retained. The
`candidateSearchTruncated` flag also reports applicable evaluator search bounds.

## Progressive discovery and search

Use `?all=true&size=20` to browse, or `?all=true&q=Ravi&size=20` to filter the full City GE
candidate population in PostgreSQL before pagination. `size` defaults to 20 and must be 1–50.
Search is case-insensitive, matches a literal partial GE full name, and also matches an exact
numeric GE User ID (the existing operational identifier returned in candidate records). `%`,
`_`, and `!` are literal search characters. Leading and trailing whitespace is trimmed;
blank, control-character and over-80-character queries are rejected. No phone, email, or other
contact identifier is searched or returned. Supplying `q`, `cursor`, or a nondefault page size
without `all=true` returns HTTP 400.

The response retains the existing recommendation fields and adds:

```json
{
  "mode": "DISCOVERY",
  "pageSize": 20,
  "scannedCandidateCount": 20,
  "nextCursor": "opaque signed token or null",
  "candidates": [
    {
      "groundExecutiveUserId": 123,
      "groundExecutiveName": "Ravi Patel",
      "rank": 0,
      "scheduledAt": "2026-10-15T05:30:00Z",
      "feasibilityStatus": "FEASIBLE"
    }
  ]
}
```

The example shows only selected candidate fields. `rank: 0` means **unranked discovery**;
database GE User ID determines order. The evaluator still chooses each GE's best feasible
time within the bounded live scheduling search. A page scans at most `size` GE candidates and
queries one additional candidate to decide whether another page exists. Therefore a page can
have fewer than `size` eligible results, or zero eligible results with a non-null `nextCursor`.
The cursor means **more GE candidates exist**, not that another eligible result is guaranteed.
No exact total count is returned. `candidateSearchTruncated` is about the scheduling evaluator's
time/reservation search bounds and is independent of `nextCursor`.

Continue with the same Session, body version, `q`, and `size`:

`POST /api/v1/operations/visit-sessions/{id}/oe-recommendations?all=true&q=Ravi&size=20&cursor={nextCursor}`

The cursor is signed, expires after 10 minutes, and binds the OE User ID, Session ID and version,
City, Team, normalized search, page size, and last scanned GE User ID. It does not grant access:
every page rechecks current authority and Session ownership. Malformed, modified, expired, or
foreign-context cursors return HTTP 400 after target authorization. Live eligibility may change
between pages; progressive completeness and nonduplication apply to unchanged underlying data.

## Selection

Use the existing `POST .../oe-recommendations/selection` endpoint with the selected GE User ID,
time, zone, Session version, and an `overrideReason` when GE or time differs from a fresh top
recommendation. Discovery order never determines the top recommendation. Booking revalidates
current staff activity, coverage, property windows, shifts, unavailability, travel, reservations,
ownership and version inside the existing transaction and locks. Stale or conflicting selections
return a recoverable conflict; a search result or cursor does not authorize booking.

If the bounded recommendation baseline has no feasible top candidate, an independently eligible
GE/time may still be booked with a nonblank `overrideReason` of at most 500 characters. The existing
decision audit records `was_override: true`, the validated reason, and null values for both the
recommended GE and recommended time. The approval response likewise has null top-recommendation
fields. This represents an absent baseline without inventing a recommendation; all booking checks
still apply.

Input validation returns HTTP 400, inaccessible Sessions return the same HTTP 404 as missing
Sessions, missing OE authority returns HTTP 403, and stale state or scheduling conflicts return
HTTP 409. Empty eligible or no-match results are successful discovery responses with empty
`candidates`; continue while `nextCursor` is non-null.
