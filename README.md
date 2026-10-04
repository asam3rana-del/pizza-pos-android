# Pizza POS: Android app (Step 16a + 16c + 23 + 28)

A small Android app (Kotlin, no extra libraries) that opens the POS screens of your shop server and can **print slips to a Bluetooth printer** paired with the phone. Waiters, cashier and kitchen all use the same screens as in a browser; only printing is new.

## 1. Build the APK (GitHub Actions, no Android Studio needed)
1. Create a new empty repository on GitHub (private is fine).
2. Upload **all files of this folder** (keep the folder structure, including the hidden `.github/workflows/build-apk.yml`).
3. Open the **Actions** tab, choose **Build APK**, press **Run workflow** (it also runs on every push).
4. When it is green, open the run and download **pizza-pos-apk** (a zip with `app-debug.apk`).
5. Send `app-debug.apk` to the phones (WhatsApp, USB, Drive). On the phone allow "Install unknown apps" for the app you opened it from, then install.

If the build fails, copy the red error text from the Actions log and send it; it is usually a one-line fix.

## 2. First start on each phone/tablet
1. Open **Pizza POS**. The Phone settings screen opens by itself the first time.
2. Type the server address shown in the server window, for example `192.168.1.10:3000`, press **Test connection**, then **Save**.
3. Log in with your staff PIN as usual.
Later: the **⚙** button in the screen header (or "Phone settings" on the login page) opens the settings again.

## 3. Bluetooth printer
1. Switch the printer on. On the phone: **Phone settings > Open Bluetooth settings** and pair it (PIN usually `0000` or `1234`).
2. Back in Phone settings, allow **Nearby devices** if Android asks.
3. Choose the printer under **Bill printer** and/or **Kitchen printer**, pick the paper (58 mm or 80 mm), press **Test print on this printer**, then **Save**.
4. From now on this phone prints the customer bill, kitchen slips, ORDER CHANGED slips and reprints on that printer. Slips are drawn as pictures, so **Urdu works**.

Notes
- A role without a Bluetooth printer prints the old way (server and LAN printer).
- Use one way per role. If the server also has a LAN printer for the same role, the slip can come out twice.
- Keep the printer close to the phone. Cheap printers can lose data if the phone is far away.
- Only phones where you chose a printer print over Bluetooth. Other phones are not affected.

## 4. What is inside
```
app/src/main/java/com/pizzashop/pos/
  MainActivity.kt      WebView with the POS screens + the bridge the screens call (window.AndroidApp)
  SettingsActivity.kt  server address, Bluetooth printers, screen option (built in code)
  BtPrinter.kt         paired devices, ESC/POS picture job, Bluetooth sending
  Prefs.kt             what the phone remembers
  AlertService.kt      background alerts: order ready (16c), new order and delivery news (23); a tapped alert opens its screen (28)
  BootReceiver.kt      starts the alerts again after the phone restarts or the app is updated (28)
  DiscoveryClient.kt   finds the POS server on the Wi-Fi (Step 16c)
.github/workflows/build-apk.yml   builds app-debug.apk
```
Requirements: Android 6.0 or newer. Package name `com.pizzashop.pos`.

## 5. Honest status (16a)
The picture-printing bytes were checked to be identical to what the server sends to a network printer, and the screen side (`slip.js`) was tested with a fake app. This Kotlin code could **not be compiled or run** where it was written (no Android SDK there), and it has not been tried on a real printer. The first GitHub build is the real compile test.

## 6a. New order and delivery alerts (Step 23)

**Phone settings > New order and delivery alerts**: *Automatic* (default: on for cashier and manager, off for waiter), *On* or *Off*. You get: **New order #N** (placed by somebody else, never your own), **Delivery failed** (rider and reason) and **Order #N delivered, collect Rs ...** (only when the rider still owes cash). Each kind has its own notification channel, so the sound can be changed in Android's notification settings. Works independently of the order-ready alert (either one keeps the background service running).

## 6. Order ready alerts (Step 16c)
When the kitchen marks an order **ready**, the phone plays a sound, vibrates and shows "Order #12 is ready, Table 4", even when the app is closed.
1. **Phone settings > Order ready alerts**: *Off*, *Only orders I took* (default, for waiters) or *All orders* (for the cashier/manager). Kitchen logins get no alerts.
2. Allow **Notifications** when Android asks (Android 13 and newer).
3. Some phones (Xiaomi, Oppo, Vivo, Samsung, Huawei...) stop background apps. Press **Battery settings** and set Pizza POS to *Not optimized / Unrestricted*, otherwise alerts can arrive late or not at all.
4. You must stay logged in. A small line "Waiting for ready orders" shows in the status bar while alerts are on. After 12 hours without real use the login ends, alerts stop and a notification says so: log in again.
The app keeps the login only in its own private storage and removes it at log out.

## 7. Find the server automatically (Step 16c)
**Phone settings > Find server automatically** fills in the server address by itself. If the app shows "Cannot reach the POS server" (for example the router gave the PC a new IP), press **Find the server** on that page. It works on the shop Wi-Fi only. If nothing is found, type the address (the server window shows it). Reasons for "nothing found": the phone is on another Wi-Fi or mobile data, the router has guest/AP isolation, or Windows Firewall blocks Node.js (UDP port 47310).

## 8. Honest status (16c)
The server side (alerts, discovery) was tested, and the phone's checking loop was tested as a line-by-line copy in a test script. The Kotlin code could **not be compiled or run on a phone** where it was written. Battery savers and the notification permission can only be checked on a real phone. The first GitHub build is the real compile test: send the red error text if it fails.

## 9. What the app shows of the newer server steps (24 to 27)
The app is a window onto the server's own screens, so **everything added to the server after Step 23 already works in it, with no app change**: table **Reservations** (Step 24, 26) and the booked marks on the Tables screen, the booking numbers in Reports (25), **Delivery areas** in the delivery order form (27). Use the app's usual header links to reach them. Download buttons (CSV for Excel) open the file in the phone's browser as before.

## 10. Step 28: alerts that survive a restart, and tap to open
- **After a restart:** when the phone restarts (or the app is updated), the alert service starts by itself if the person is still logged in and alerts are on. Nobody has to open the app first. If you logged out, nothing starts (that is on purpose). On Xiaomi/Oppo/Vivo/Huawei phones you may also have to allow **Autostart** for Pizza POS in the phone's own settings, otherwise the phone does not let the app wake up after a restart.
- **Tap an alert:** "New order" and "Order ready" open **Today's orders**; "Delivery failed" and "collect Rs ..." open the **delivery board**. (Needs the server of Step 28 for the Today's orders tab; with an older server the cashier screen simply opens.)
- Version is now **1.2**. Install the new APK over the old one: login, server address and printers stay.

## 11. Honest status (Step 28)
The new Kotlin (BootReceiver, the screen hand-over in MainActivity and AlertService) was checked only for balanced brackets, names and imports. It could **not be compiled or run** where it was written, and the restart behaviour depends on the phone maker, so it can only be proved on a real phone: restart the phone with alerts on and place an order from another device. The first GitHub build is the real compile test: send the red error text if it fails.
