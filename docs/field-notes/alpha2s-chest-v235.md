# Field notes: Alpha 2 with chest firmware V235 (open-alpha2 beta 7)

These notes come from a second robot that runs open-alpha2 beta 7. They show where this robot behaves differently from the robot that open-alpha2 was built on. All results were tested on hardware on 2026-10-30.

## Robot

| Item | Value |
|---|---|
| Board | RK3288, Android 5.1.1, build `LMY49F`, `userdebug`, kernel 3.10.0 from 2016-12-01 |
| Chest firmware | `ALPHA2S-CHEST-A-V235-161221` (the open-alpha2 docs use V352) |
| Camera | Sony IMX214 (`/system/etc/cam_board.xml`), no hardware face detection (`hardwareMaxFaces` 0) |
| Speaker | C-Media CM108B USB audio chip on an internal USB hub |
| UBTECH apps | All disabled with `pm disable` |

## Findings

1. **Start-up LED animation.** With `com.ubtechinc.alpha2services` disabled, the eye and head LEDs keep the rotating start-up animation after each boot. `/api/led/eye/set?preset=stop` and `/api/led/head/set?preset=stop` return OK, but the animation continues. The head MCU STARTUP command (`f8 8f 07 05 00 05 11 ed` on `/dev/ttyS3`, as in UBTECH `StartUpOps`) does not stop it either. `/api/direct/led/off` stops it. Suggestion: call `localServices.ledOff()` once when the app starts.
2. **`led/eye/set` and `led/head/set` need `brightness`.** Without `brightness` (1 to 9) the API returns `missing required parameter: brightness`. With it, red, green and blue work on both.
3. **LEDs with no effect on this robot:** `led/mouth/set` (breathing), `led/mute/set` (chest cmd 68), `led/pad/set`, `led/wifi/set`. The mouth LED does blink while the robot talks. The chest light blinks slowly green all the time.
4. **Angle reads.** `servo/back-angle` (cmd 0x17) works for the 8 kg servos (2, 3, 5, 6, 17 to 20) but gets no answer from the 20 kg servos (1, 4, 7 to 16). `servo/version-all` (cmd 14) answers for all 20. `servo/angle-restore` works for all servos and holds the joint.
5. **Right thumb stop.** Servo 17 blocks at 114 on this robot (travel table: 95 to 125). The stock `BackStand` and dance actions ask for 121 to 122, so the servo pushes against the stop. `UbxPlayer` clamps only to 0..255. A per-robot limit table in the player would protect the thumbs.
6. **Wi-Fi on a WPA2/WPA3 mixed network.** The robot connects, then drops with `WPA: Failed to configure IGTK to the driver`. The 2016 Broadcom driver does not support protected management frames (802.11w). `ieee80211w 0` on the network fixes it. The driver also starts with country code CN. `settings put global wifi_country_code NL` (or your country) changes it.
7. **Stand-up works.** A copy of `BackStand.ubx` with all values inside the travel table, played through `direct/ubx/play?path=...`, stood the robot up from its back.

## Head keys

The head keys blink an LED by themselves. Android got two `gamepad_key` events (code 0, "unmapped") in the first test only. Later presses gave no event and did not change the volume. This is still open.
