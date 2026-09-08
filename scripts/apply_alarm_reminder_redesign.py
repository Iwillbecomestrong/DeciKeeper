from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: str, old: str, new: str) -> bool:
    target = ROOT / path
    text = target.read_text(encoding="utf-8")
    if new in text:
        print(f"already applied: {path}")
        return False
    if old not in text:
        raise SystemExit(f"expected patch anchor not found in {path}: {old[:100]!r}")
    target.write_text(text.replace(old, new, 1), encoding="utf-8")
    print(f"patched: {path}")
    return True


scheduler = "app/src/main/java/com/zhizhi/app/manager/AlarmScheduler.kt"

replace_once(
    scheduler,
    '''        val scheduleToken: String = "",
        val stage1CustomBanks: Map<String, Int> = emptyMap(),
        val stage2CustomBanks: Map<String, Int> = emptyMap()
    )''',
    '''        val scheduleToken: String = "",
        val stage1CustomBanks: Map<String, Int> = emptyMap(),
        val stage2CustomBanks: Map<String, Int> = emptyMap(),
        val stage2UseIndependentAlertPolicy: Boolean = false,
        val stage2AlarmMode: AlarmMode = AlarmMode.VIBRATE_AND_RING,
        val stage2VibrationMode: VibrationMode = VibrationMode.PROGRESSIVE,
        val stage2VibrationIntensity: Int = 7,
        val stage2RingVolume: Int = 7
    )'''
)

replace_once(
    scheduler,
    '''        autoEscalate: Boolean = false,
        stage1CustomBanks: Map<String, Int> = emptyMap(),
        stage2CustomBanks: Map<String, Int> = emptyMap()
    ): AlarmEntry {''',
    '''        autoEscalate: Boolean = false,
        stage1CustomBanks: Map<String, Int> = emptyMap(),
        stage2CustomBanks: Map<String, Int> = emptyMap(),
        stage2UseIndependentAlertPolicy: Boolean = false,
        stage2AlarmMode: AlarmMode = AlarmMode.VIBRATE_AND_RING,
        stage2VibrationMode: VibrationMode = VibrationMode.PROGRESSIVE,
        stage2VibrationIntensity: Int = 7,
        stage2RingVolume: Int = 7
    ): AlarmEntry {'''
)

replace_once(
    scheduler,
    '''            autoEscalate,
            stage1CustomBanks = stage1CustomBanks,
            stage2CustomBanks = stage2CustomBanks
        )''',
    '''            autoEscalate,
            stage1CustomBanks = stage1CustomBanks,
            stage2CustomBanks = stage2CustomBanks,
            stage2UseIndependentAlertPolicy = stage2UseIndependentAlertPolicy,
            stage2AlarmMode = stage2AlarmMode,
            stage2VibrationMode = stage2VibrationMode,
            stage2VibrationIntensity = stage2VibrationIntensity,
            stage2RingVolume = stage2RingVolume
        )'''
)

replace_once(
    scheduler,
    '''        alarm.stage2CustomBanks.forEach { (k, v) -> s2BanksObj.put(k, v) }
        obj.put("stage2CustomBanks", s2BanksObj)
        return obj''',
    '''        alarm.stage2CustomBanks.forEach { (k, v) -> s2BanksObj.put(k, v) }
        obj.put("stage2CustomBanks", s2BanksObj)
        obj.put("stage2UseIndependentAlertPolicy", alarm.stage2UseIndependentAlertPolicy)
        obj.put("stage2AlarmMode", alarm.stage2AlarmMode.name)
        obj.put("stage2VibrationMode", alarm.stage2VibrationMode.name)
        obj.put("stage2VibrationIntensity", alarm.stage2VibrationIntensity)
        obj.put("stage2RingVolume", alarm.stage2RingVolume)
        return obj'''
)

replace_once(
    scheduler,
    '''        val progressiveMaxRingVolume = obj.optInt("progressiveMaxRingVolume", 10).coerceIn(1, 10)
        val autoEscalate = obj.optBoolean("autoEscalate", false)

        return AlarmEntry(''',
    '''        val progressiveMaxRingVolume = obj.optInt("progressiveMaxRingVolume", 10).coerceIn(1, 10)
        val autoEscalate = obj.optBoolean("autoEscalate", false)
        val stage2UseIndependentAlertPolicy = obj.optBoolean("stage2UseIndependentAlertPolicy", false)
        val stage2AlarmMode = runCatching {
            AlarmMode.valueOf(obj.optString("stage2AlarmMode", alarmMode.name))
        }.getOrElse { alarmMode }
        val stage2VibrationMode = runCatching {
            VibrationMode.valueOf(obj.optString("stage2VibrationMode", vibrationMode.name))
        }.getOrElse { vibrationMode }
        val stage2VibrationIntensity = obj.optInt("stage2VibrationIntensity", vibrationIntensity).coerceIn(1, 10)
        val stage2RingVolume = obj.optInt("stage2RingVolume", ringVolume).coerceIn(1, 10)

        return AlarmEntry('''
)

replace_once(
    scheduler,
    '''            scheduleToken,
            stage1CustomBanks = s1Banks,
            stage2CustomBanks = s2Banks
        )''',
    '''            scheduleToken,
            stage1CustomBanks = s1Banks,
            stage2CustomBanks = s2Banks,
            stage2UseIndependentAlertPolicy = stage2UseIndependentAlertPolicy,
            stage2AlarmMode = stage2AlarmMode,
            stage2VibrationMode = stage2VibrationMode,
            stage2VibrationIntensity = stage2VibrationIntensity,
            stage2RingVolume = stage2RingVolume
        )'''
)

print("alarm reminder redesign patch complete")
