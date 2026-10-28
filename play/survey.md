# "Suggest a feature" survey (Google Form)

The app's **Suggest a feature** button opens this form in the browser. The app collects nothing itself; answers land
in your Google Sheet.

## Setting it up (5 minutes)
1. forms.google.com → Blank form, signed in as gdpusd26@gmail.com.
2. Title: **GBP/USD Predictor: what would you like to see?**
   Description: *Takes about 2 minutes. Anonymous: don't include personal details.*
3. Settings → Responses: turn **Collect email addresses OFF** and **Limit to 1 response OFF** (no sign-in needed).
4. Add the questions below. Then Responses → Link to Sheets.
5. Send → link icon → tick "Shorten URL" → copy. Give me that link and I'll put it in the app (`FEEDBACK_FORM_URL` in
   MainActivity.kt), then the button appears.

## Questions
1. **How often do you open the app?** (Multiple choice) Every day · A few times a week · Once a week or less · Only when I get an alert
2. **What do you use it for?** (Checkboxes) Holiday / travel money · Sending money abroad · Business payments · Trading or investing · Just curious · Other
3. **Which parts do you find useful?** (Checkboxes) Tomorrow's forecast · 1–14 day forecasts · Rate chart · Accuracy screen · News sentiment and headlines · Price alerts
4. **What would you most like added?** (Checkboxes, pick up to 3) Other currency pairs (EUR, etc.) · Home-screen widget · Daily forecast notification · Longer chart history · Plain-English "why" behind each forecast · Dark mode · Fewer or smaller ads · Other
5. **How much do you trust the forecasts?** (Linear scale 1–5, 1 = not at all, 5 = completely)
6. **Was anything confusing or broken?** (Paragraph)
7. **Anything else you'd like to see?** (Paragraph)
