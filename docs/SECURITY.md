# Security

## Reporting a Vulnerability
If you find a security problem in Hisab, do not open a public GitHub issue. Contact the maintainer directly with details and steps to reproduce. The problem will be fixed before any public disclosure.

## What Hisab Must Do

Baseline: a mobile security review mapped to OWASP MASVS categories — storage, cryptography, authentication, network, platform interaction, code, resilience, privacy. (Not labeled "MASVS Level 1/2" — the current MASVS dropped that verification-level model.)

### Login and Sessions
- Secure login.
- Sessions expire and can be safely renewed.
- Optional PIN or fingerprint lock for the app itself.
- The backend always derives which shop a request belongs to from the authenticated session, never from a shop_id the client sends. This exists from the first endpoint — it is not something added later during hardening.

### On the Device
- Sensitive keys and tokens are stored in the Android Keystore, not in plain files.
- App storage is protected.
- No API secret is ever hard-coded into the app.

### Network
- All traffic uses HTTPS.
- Every API request is checked for a valid login token.
- All input from the app is checked on the server before use.
- Every request is checked against what that user/shop is allowed to do.

### Backend
- One shop can never read or change another shop's data (tenant isolation).
- Backend accounts and services use the minimum access they need, nothing more.
- Sensitive actions are rate-limited to stop abuse.
- Passwords are stored using a secure hash, never in plain text.
- Admin-only actions are protected and logged.

## What Hisab Must Not Collect
- National ID (NID).
- Facial recognition data.
- A single "global" customer identity shared across shops.
- A cross-shop customer blacklist.
- Contact-book access that is not needed.

## Research Data
Any data used for research or testing is synthetic or anonymized, unless real users have given consent through a proper ethics process.

## Full Details
The complete list of security requirements is in `PRD.md`, section 19.
