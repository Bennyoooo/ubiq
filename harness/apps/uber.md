---
package: com.ubercab
---
Uber. Start a ride with `request_ride` (destination, optional pickup): it opens Uber with the trip filled in, so you skip the "Where to?" search.
- The ride options screen lists products (UberX, Comfort, UberXL, ...) with a price and an arrival time each. Read them and tell the user the cheapest sensible one: product, price, minutes away. Ask before booking unless they already said to book.
- Tapping a product only selects it. The button that books is "Request <product>", "Confirm <product>" or, on the pickup map, "Confirm pickup": call approve_payment (price, "Uber") right before it.
- After booking, the screen shows the driver, car and plate: report those. If Uber asks to confirm the pickup spot first, that is still part of booking.
- If the drop-off is wrong or missing (a name Uber could not place), tap the destination field and type the address.
- Sign-in, card or "verify your account" screens: ask_user; the user has to do those.
