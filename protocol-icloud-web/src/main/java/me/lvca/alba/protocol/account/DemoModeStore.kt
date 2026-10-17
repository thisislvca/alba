package me.lvca.alba.protocol.account

/** Demo is an explicit user choice, never a fallback for missing credentials. */
interface DemoModeStore {
    var enabled: Boolean
}

class MemoryDemoModeStore : DemoModeStore {
    override var enabled: Boolean = false
}
