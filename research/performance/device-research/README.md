# Device Research (Step 18)

Evidence behind `DECISIONS.md` D028: `minSdk = 26`, and the low-end reference device (Tecno Spark Go 2, 3 GB + 64 GB).

StatCounter's pages change every month, so the exact numbers used are saved here.

| File | What it is |
| --- | --- |
| `statcounter-bd-mobile-android-version-2026-02_2026-08.csv` | Android version share, Bangladesh, mobile, Feb–Aug 2026 (percent of page views) |
| `statcounter-bd-mobile-vendor-2026-02_2026-08.csv` | Phone brand share, Bangladesh, mobile, Feb–Aug 2026 (percent of page views) |

Downloaded 2026-09-13 from StatCounter's CSV export (`gs.statcounter.com/chart.php?...&csv=1`). Source: [StatCounter Global Stats](https://gs.statcounter.com/).

## How the D028 numbers come from these files

- Supported by minSdk 26 = sum of columns `8.0 Oreo` and newer, including `17.0`.
- Not supported = `4.4 KitKat`, `5.0`, `5.1`, `6.0`, `7.0`, `7.1`.
- The 7-month average = the plain mean of the seven monthly rows.

## Limits

- These are page-view shares, not phones owned or phones sold. For sales by brand, D028 uses IDC figures instead.
- The brand file has a large `Unknown` group (9–22%). Local brands such as Symphony and Walton probably sit partly inside it.

## Still to add when the reference phone is bought (M7)

- `reference-device.md`: build number, security patch date, `getprop ro.config.low_ram`, total RAM from `/proc/meminfo`, and the state of the memory-extension setting.
- The pilot device record — see `docs/RESEARCH_PLAN.md`.
