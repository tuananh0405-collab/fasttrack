# Mock Final Project: AAOS VehicleScope

A Vehicle Data Monitor — a privileged AAOS system app that reads live vehicle properties via
CarPropertyManager, exposes a bindable AIDL service for a separate client app to subscribe to
property events, and falls back to a simulated data provider on the emulator.

Covers all four training modules: AAOS architecture, Car API + permissions, IPC/AIDL, and VHAL simulation.

---

## Architecture

```
┌──────────────────────────────────────────────────────────┐
│  VehicleScope App (privileged system app)                │
│                                                          │
│  ┌─────────────────┐    ┌──────────────────────────────┐ │
│  │  DashboardActivity│    │  VehicleDataService          │ │
│  │  (Car lifecycle) │    │  (foreground service)        │ │
│  │  - renders props │◄──►│  - binds to android.car.Car  │ │
│  │  - CarPropertyMgr│    │  - exposes IVehicleData.aidl │ │
│  └─────────────────┘    │  - death recipient / reconn. │ │
│                          └──────────┬───────────────────┘ │
└─────────────────────────────────────┼────────────────────┘
                                      │ Binder
                  ┌───────────────────▼──────────────────┐
                  │  VehicleScopeClient (separate APK)   │
                  │  - binds to IVehicleData.aidl        │
                  │  - registers callback                │
                  │  - displays live feed                │
                  └──────────────────────────────────────┘
                                      ▲
                  ┌───────────────────┴──────────────────┐
                  │  FakeVhalProvider (test module)       │
                  │  - emits scripted property events     │
                  │  - replaces CarPropertyManager        │
                  │    behind a DataSource interface      │
                  └──────────────────────────────────────┘
```

---

## Repo Structure

```
VehicleScope/
├── app/                          # DashboardActivity + CarConnectionManager
├── service/                      # VehicleDataService + AIDL definitions
│   └── src/main/aidl/
│       ├── IVehicleDataService.aidl
│       └── IVehicleDataCallback.aidl
├── client/                       # VehicleScopeClient APK
├── fake-vhal/                    # FakeVhalDataSource + scenario scripts
├── privapp-permissions/          # vehiclescope.xml whitelist
└── docs/
    ├── AAOS_DIFF.md
    ├── data_flow_trace.md
    └── vhal_summary.md
```

---

## Module Breakdown

### Module 1 — AAOS Environment

**Task 1.1 — Project skeleton on emulator**
- Create the Android Studio project targeting `android.car` APIs
- Boot the AAOS emulator, confirm ADB connectivity
- Add a `logcat` filter tag (`VehicleScope`) and enforce it throughout — every log uses this tag,
  structured as `TAG/component: message`
- Write `docs/AAOS_DIFF.md` noting 3 concrete ways this app would fail deployed as a plain phone app
  (no `android.car` library, no Car lifecycle, no VHAL)

**Task 1.2 — RRO warm-up (bonus)**
- Create a minimal Runtime Resource Overlay that swaps the dashboard background color
- Deploy with `adb install` and verify the overlay activates

---

### Module 2 — CarService, Car API, Permissions

**Task 2.1 — Privileged app setup**
- Declare `android.car.permission.CAR_SPEED`, `CAR_FUEL_LEVEL`, `CAR_GEAR` in `AndroidManifest.xml`
  with `protectionLevel="signature|privileged"`
- Create `privapp-permissions/privapp-permissions-vehiclescope.xml`
- Document why a normal `uses-permission` alone will not grant these at runtime on AAOS

**Task 2.2 — Car connection & lifecycle**

Implement `CarConnectionManager` (wrapper class) that:
- Calls `Car.createCar(context, handler)` and stores the instance
- Responds to `CarServiceLifecycleListener` — `ON_LIFECYCLE_CHANGED_READY` / `NOT_READY`
- Tears down CarPropertyManager subscriptions on `NOT_READY` and re-subscribes on `READY`
- Wires into `DashboardActivity` so the UI reflects connection state

**Task 2.3 — CarPropertyManager subscription**

Subscribe to at minimum:
- `VehiclePropertyIds.PERF_VEHICLE_SPEED`
- `VehiclePropertyIds.FUEL_LEVEL`
- `VehiclePropertyIds.GEAR_SELECTION`

Use `registerCallback` with a suitable update rate. Handle `onErrorEvent` — log property ID and
error code. Run `adb shell dumpsys car_service --services CarPropertyService` and annotate the
output in `docs/data_flow_trace.md`.

**Task 2.4 — Data flow trace**

Write `docs/data_flow_trace.md`: for `PERF_VEHICLE_SPEED`, trace the call from
`CarPropertyManager.getProperty()` → CarService binder call → VHAL `get()` → emulator's fake VHAL
implementation. Identify where the Binder boundary is.

---

### Module 3 — IPC & AIDL

**Task 3.1 — Define the AIDL interface**

```aidl
// IVehicleDataService.aidl
interface IVehicleDataService {
    void registerCallback(IVehicleDataCallback cb);
    void unregisterCallback(IVehicleDataCallback cb);
    float getCurrentSpeed();
    int getVersion();   // interface evolution: always add this
}

// IVehicleDataCallback.aidl
// oneway: callbacks must not block the server thread
oneway interface IVehicleDataCallback {
    void onSpeedChanged(float speedMps);
    void onFuelLevelChanged(float fraction);   // 0.0–1.0
    void onGearChanged(int gear);
}
```

**Task 3.2 — VehicleDataService implementation**
- `Service` subclass, returns the `IVehicleDataService.Stub` from `onBind`
- Maintains a `RemoteCallbackList<IVehicleDataCallback>` for built-in dead-binder cleanup
- Adds `IBinder.DeathRecipient` on each registered callback's binder — on `binderDied`, remove and log
- Runs as a foreground service with a persistent notification (AAOS requirement)

**Task 3.3 — VehicleScopeClient (separate APK)**
- Binds to `VehicleDataService` by explicit intent
- Implements `ServiceConnection` with reconnection backoff (exponential, cap at 30 s)
- Registers `IVehicleDataCallback` stub, renders incoming events to a `TextView`
- On `onServiceDisconnected`, clears displayed data and starts reconnect loop

**Task 3.4 — Interface versioning exercise**
- Add `getBatteryLevel(): float` to `IVehicleDataService.aidl` (v2)
- Keep client on the old interface (v1)
- Demonstrate safe upgrade path: client calls `getVersion()` before `getBatteryLevel()` to guard
  against `AbstractMethodError`

---

### Module 4 — VHAL Concepts & Simulation

**Task 4.1 — DataSource abstraction**

```kotlin
interface VehicleDataSource {
    fun start(listener: PropertyListener)
    fun stop()
}
```

Implement `CarApiDataSource` (real CarPropertyManager) and `FakeVhalDataSource` (emulated).
Select via `SystemProperties.get("vehiclescope.fake_vhal", "false")` — no scattered `BuildConfig.DEBUG` checks.

**Task 4.2 — FakeVhalDataSource**

Drives a scripted scenario on a coroutine:
- Speed ramps 0 → 120 km/h over 30 s
- Fuel drains slowly
- Gear shifts at speed thresholds

Emits events through the same `PropertyListener` interface as the real source so the rest of the
app is unaware of the substitution.

**Task 4.3 — VHAL written summary**

Write `docs/vhal_summary.md` covering:
- What a VHAL property area means (global vs per-seat vs per-mirror)
- How an OEM adds a custom vendor property
- Why the app must never assume a property exists — always check `getSupportedPropertyIds()` first

---

## Deliverables & Grading Rubric

| Area | Deliverable | Weight |
|---|---|---|
| Environment | Emulator running, logcat discipline, `AAOS_DIFF.md` | 10% |
| Car API | CarConnectionManager, lifecycle handling, property subscriptions | 25% |
| Permissions | Whitelist XML, manifest declarations, written explanation | 15% |
| AIDL / IPC | Both `.aidl` files, service impl, client with reconnect, versioning demo | 30% |
| VHAL simulation | `VehicleDataSource` abstraction, `FakeVhalDataSource` scenario | 15% |
| Data flow trace | Written trace + annotated `dumpsys` output | 5% |

---

## Key "Gotcha" Checkpoints

Things most trainees get wrong — your project must demonstrate you handled them:

1. **Car.createCar vs CarServiceLifecycleListener** — never assume the Car object is immediately
   ready; always wait for the lifecycle callback before calling `getCarManager()`
2. **`oneway` callbacks** — callbacks into your client run on the Binder thread pool, not the main
   thread; all UI updates must be `post()`ed
3. **`RemoteCallbackList` iteration** — must call `beginBroadcast()` / `finishBroadcast()` as a
   pair even if iteration throws
4. **Death recipient registration timing** — link the death recipient *before* sending the binder
   back to the client, not after
5. **Area ID 0 vs `VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL`** — passing the wrong area ID to
   `getProperty()` silently returns null on some builds

---

## References

| Topic | Link |
|---|---|
| AOSP setup | https://source.android.com/docs/setup/about |
| AAOS docs | https://source.android.com/docs/automotive |
| Android for Cars | https://developer.android.com/cars |
| AAOS platform | https://developer.android.com/training/cars/platforms/automotive-os |
| AAOS release notes | https://source.android.com/docs/automotive/start/releases |
| Virtualization | https://source.android.com/docs/core/virtualization |
| Android Runtime | https://source.android.com/docs/core/runtime |
| Permissions | https://source.android.com/docs/core/permissions |
| SELinux | https://source.android.com/docs/security/features/selinux |
| Car Service API | https://developer.android.com/reference/android/car/Car |
| Privileged allowlist | https://source.android.com/docs/core/permissions/perms-allowlist |
| AIDL | https://source.android.com/docs/core/architecture/aidl |
| Binder | https://source.android.com/docs/core/architecture/ipc/binder-overview |
