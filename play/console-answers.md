# Play Console answers (draft: tick these off in the Console; confirm wording against the live forms)

## App content
| Form | Answer |
|---|---|
| Privacy policy | Use the hosted URL of `privacy-policy.html` |
| Ads | **Yes, the app contains ads** (Google AdMob banner) |
| App access | All functionality available without login or special access |
| Target audience | **18 and over only**; not designed for children |
| Content rating (IARC questionnaire) | Category: Utility/Reference. No violence, sexual content, profanity, drugs, gambling, user-generated content, location sharing or purchases. Expected rating: Everyone / PEGI 3 |
| News apps | No (it is not a news app; it shows a sentiment score, not articles) |
| Government apps | No |
| Financial features | Declare an information-only app: no payments, loans, trading, banking or crypto. Confirm the exact checkbox in the Console form |
| Advertising ID | **Yes**: used for advertising (AdMob). The app declares the AD_ID permission through the AdMob SDK |
| Health / COVID / etc. | No |

## Data safety
**Does the app collect or share user data?** Yes (through the AdMob SDK only).
**Is all data encrypted in transit?** Yes (HTTPS only).
**Can users request deletion?** There is no account; all local data is removed by uninstalling.

| Data type | Collected | Shared | Purpose | Optional? |
|---|---|---|---|---|
| Device or other IDs (Advertising ID) | Yes | Yes (Google AdMob) | Advertising or marketing | Required for ads; UK/EEA users are asked for consent |
| App interactions / diagnostics (ad impressions and clicks, crash/performance info sent by the SDK) | Yes | Yes (Google AdMob) | Advertising, fraud prevention | Required for ads |
| Approximate location (derived from IP by the SDK) | Yes | Yes (Google AdMob) | Advertising | Required for ads |
| Everything else (name, email, financial info, contacts, photos, ...) | **No** | No | n/a | n/a |

Notes for the form: the app itself never sends any data to the developer. Forecast history, settings and the user's own
Alpha Vantage key stay on the device (not "collected"). The Alpha Vantage key is sent only to Alpha Vantage by the user's
own choice, to fetch the exchange rate for that user.

## Store settings
- Free app, English (UK), category Finance, contact email [CONTACT EMAIL], website optional.
- Countries: all, or start with the UK.
- Do NOT upload an AAB built with Google's test ad ids (the build refuses to make one).
