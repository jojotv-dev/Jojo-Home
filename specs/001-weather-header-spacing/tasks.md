# Tasks: Aérer et redimensionner le module météo

**Input**: Design documents from `/specs/001-weather-header-spacing/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [quickstart.md](quickstart.md)

**Tests**: No automated test tasks are required by the specification; validation is compilation, static checks and real TV/emulator rendering because the acceptance criteria are primarily visual and D-pad based.

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Establish the implementation baseline without disturbing the dirty checkout.

- [X] T001 [P] Review the current weather/header implementation and existing diff in `app/src/main/java/com/jojotv/home/MainActivity.java` before editing
- [X] T002 [P] Record the current nominal and VPN-visible header behavior against `specs/001-weather-header-spacing/quickstart.md` without changing application code

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Preserve existing weather data, refresh, focus and click behavior before changing layout measurements.

- [X] T003 Trace `WeatherWidgetViews`, `refreshWeather(...)`, `scheduleWeatherAutoRefresh()` and the weather click/focus listeners in `app/src/main/java/com/jojotv/home/MainActivity.java`
- [X] T004 Define the shared layout invariants in `app/src/main/java/com/jojotv/home/MainActivity.java`: one current-weather row above one forecast row, no source/frequency/navigation changes, and no clipping of adjacent header items

**Checkpoint**: Existing weather content and interactions are understood and protected; user-story implementation can begin.

---

## Phase 3: User Story 1 - Lire les prévisions depuis le canapé (Priority: P1) 🎯 MVP

**Goal**: Make the three forecast groups readable and visually separated at TV viewing distance while keeping the city-temperature block aligned above them.

**Independent Test**: With three forecast days available, render the home header and confirm from about 2 meters that each day, icon and temperature are identifiable without horizontal compression or overlap.

### Implementation for User Story 1

- [X] T005 [US1] Restructure the forecast-row construction in `app/src/main/java/com/jojotv/home/MainActivity.java` so each day/icon/temperature remains a coherent visual group
- [X] T006 [US1] Add regular horizontal separation between forecast groups in `app/src/main/java/com/jojotv/home/MainActivity.java` and remove nominal horizontal squeezing of the complete forecast line
- [X] T007 [US1] Preserve centered alignment between the current city-temperature row and the full forecast row in `app/src/main/java/com/jojotv/home/MainActivity.java`
- [X] T008 [US1] Retain the existing forecast content bindings and weather icon updates in `app/src/main/java/com/jojotv/home/MainActivity.java` while applying the new layout

**Checkpoint**: User Story 1 is independently demonstrable with three forecasts and no adjacent-header regression in the nominal layout.

---

## Phase 4: User Story 2 - Préserver l’équilibre de la barre supérieure (Priority: P2)

**Goal**: Allocate the available space between Recherche and Local/NAS to weather without clipping or displacing neighboring header controls.

**Independent Test**: Render the header with VPN hidden and visible, then confirm that weather, Recherche, Local/NAS and all visible system controls remain fully visible and focusable.

### Implementation for User Story 2

- [X] T009 [US2] Adjust the weather preferred/minimum width calculation in `app/src/main/java/com/jojotv/home/MainActivity.java` to use actual remaining header width before reducing forecast readability
- [X] T010 [US2] Reconcile weather width with Local/NAS widths, optional VPN visibility and computed inter-item gaps in `app/src/main/java/com/jojotv/home/MainActivity.java`
- [X] T011 [US2] Limit any forecast compaction fallback in `app/src/main/java/com/jojotv/home/MainActivity.java` to genuinely constrained layouts and ensure it never clips the final forecast value
- [X] T012 [US2] Verify and preserve horizontal focus wrapping and click targets for the complete header in `app/src/main/java/com/jojotv/home/MainActivity.java`

**Checkpoint**: User Stories 1 and 2 work independently in nominal and VPN-visible header configurations.

---

## Phase 5: User Story 3 - Conserver un affichage robuste (Priority: P3)

**Goal**: Keep the weather header stable for missing forecasts, long city names and unusual temperature values.

**Independent Test**: Exercise zero, one, two and three forecasts plus long city/negative multi-digit temperatures, and confirm clean bounded rendering without fake values or overlap.

### Implementation for User Story 3

- [X] T013 [US3] Ensure forecast item visibility and spacing remain clean when fewer than three forecasts are returned in `app/src/main/java/com/jojotv/home/MainActivity.java`
- [X] T014 [US3] Bound long city labels and multi-character temperatures without hiding neighboring header controls in `app/src/main/java/com/jojotv/home/MainActivity.java`
- [X] T015 [US3] Verify loading and unavailable-weather states retain the existing placeholder/error behavior without layout artifacts in `app/src/main/java/com/jojotv/home/MainActivity.java`

**Checkpoint**: All three user stories are independently testable and the weather widget remains robust across data and width edge cases.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Compile, inspect and validate the complete TV experience.

- [X] T016 [P] Run `git diff --check` for `app/src/main/java/com/jojotv/home/MainActivity.java` and resolve whitespace or patch errors before build
- [X] T017 Build the debug APK with `:app:assembleDebug --no-daemon --console=plain` and confirm the artifact at `app/build/outputs/apk/debug/app-debug.apk`
- [ ] T018 Execute the nominal, VPN-visible, robustness and navigation scenarios in `specs/001-weather-header-spacing/quickstart.md` on a TV or representative TV emulator
- [ ] T019 Confirm the three-width rendering, 2-meter readability, no-clipping and no-perceptible-jump success criteria from `specs/001-weather-header-spacing/spec.md`
- [ ] T020 [P] Update `specs/001-weather-header-spacing/quickstart.md` with observed device/configuration results and clearly separate static, build and runtime evidence

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies; inspect and baseline the existing dirty checkout first.
- **Foundational (Phase 2)**: Depends on Setup; blocks all user-story edits until the existing refresh/focus contracts are understood.
- **User Stories (Phases 3-5)**: Depend on Foundational and proceed in priority order because they touch the same `MainActivity.java` layout path.
- **Polish (Phase 6)**: Depends on all desired user stories being implemented.

### User Story Dependencies

- **User Story 1 (P1)**: Starts after Phase 2; delivers the MVP and establishes the readable forecast-row structure.
- **User Story 2 (P2)**: Depends on US1’s forecast structure because width allocation must be measured against the new row; independently validated with VPN hidden/visible.
- **User Story 3 (P3)**: Depends on US1 and US2 because edge-case sizing must use the final row and adaptive width rules.

### Parallel Opportunities

- T001 and T002 can run in parallel because both are read-only baseline tasks.
- T005 and T008 can be prepared in parallel, but edits to `MainActivity.java` should be merged sequentially to avoid conflicts.
- T016 and preparation of the quickstart evidence can run in parallel after implementation; T017 must follow T016.
- Runtime checks for VPN hidden, VPN visible and long-data cases can be executed in parallel once the APK is installed.

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phases 1-2.
2. Implement Phase 3 and stop at its checkpoint.
3. Build and validate the three-day forecast row at TV distance.
4. Continue to US2 only after the nominal layout is readable.

### Incremental Delivery

1. Readable forecast groups (US1).
2. Responsive header allocation and VPN/non-VPN balance (US2).
3. Missing-data and long-value robustness (US3).
4. Full build and real-TV validation.

## Notes

- Every implementation task names the concrete source file `app/src/main/java/com/jojotv/home/MainActivity.java`.
- No task changes weather networking, persistence, permissions or navigation.
- The final visual/D-pad validation is mandatory; compilation and static checks alone are not completion evidence.
