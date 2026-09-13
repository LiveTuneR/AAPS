# Apex7 bolus safety: итог реализации

Статус: `EXPERIMENTAL_DEVICE_VALIDATION_REQUIRED`.

## Автоматически подтверждено

- Accepted run: `34774277312`, commit `6a7d377d9c4707dd8a34219f2dc71325b979c6f1`.
- Тесты: `2802`, failures `0`, errors `0`, skipped `0`.
- Phone APK SHA-256: `ebc78035e1db3507c91f8973a756388ca7fc2accd9f5452aa16ba212c5d32457`.
- Wear APK SHA-256: `2e632e4535f820d4c45199d48546883d63216185ad04372559e2f8ded5eeff94`.
- Оба APK: V2 signer SHA-256 `1b20d5c3807e9e6d895728d68099e21801ec05f860d4cc457eee25e530a8a084`.

## Реализовано

- Durable FSM разделяет `Completed` и подтверждение постоянной историей. Журнал сохраняется до первого BLE write и fail-closed восстанавливается после restart, включая повреждённый файл.
- Неоднозначный bolus не повторяется автоматически. Unresolved gate блокирует все bolus-источники, extended bolus и повышающий TBR; чтение, reconciliation и уменьшающие действия остаются доступны.
- Reconciliation: две ограниченные попытки LatestBoluses, затем полный BolusHistory; совпадение использует дозу в шагах, временную опору и identity помпы. Stale/non-monotonic/duplicate/cursor anomalies логируются.
- Runtime `inProgressBolus` отделён от durable uncertainty, поэтому busy не зависает. Overview и Apex diagnostics показывают requested/live/history/state/gate.
- Телеметрия получила durable admission/commit WAL, time-ranged integrity ledger, фазово-корректный CGM gap detector, индекс gzip-сегментов и единый 1/4/7-day support bundle с AAPS logs, Apex trace и operation journal.
- Добавлены replay реального случая 9.25 U / 370 steps, тесты normal/partial/cancel/mismatch/stale/restart/no-resend и OFF-BODY протокол приемки.

## Автоматически проверено

- Kotlin production compilation: PASS.
- ApexBolusReconciliationTest: 9/9 PASS.
- ApexBolusRestartSafetyTest: 9/9 PASS.
- ApexHistoryAnomalyTest и ApexCommDirectorTest: PASS.
- Новые telemetry tests: phase offset, ranged integrity, restart admission loss: 3/3 PASS.
- Полный Linux CI, phone/Wear release build и проверка подписи заполняются ссылкой и хешами после GitHub run.

## Не проверено физически

- Реальная работа мотора и фактически доставленный объём Apex.
- Поведение постоянной истории конкретной прошивки `1.1.1.0 / 4.12`.
- BLE fault windows и process-kill во время реальной подачи.
- Работа Medtrum hardware.

До просмотра физических логов сборка не является клинически принятой. Приёмка выполняется только OFF-BODY по `doc/APEX_BOLUS_DEVICE_ACCEPTANCE_RU.md`.
