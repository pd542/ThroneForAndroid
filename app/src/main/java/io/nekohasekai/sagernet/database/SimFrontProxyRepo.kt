package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The SIM front-proxy bindings: `sim_front_proxies` in editor order.
 *
 * Writes go through Room and are visible to the service process by Room's multi-instance
 * invalidation, but the running core is only rebuilt when [notifyChanged] runs, so the service can
 * tell an edit that needs a restart from a reload of the UI.
 */
object SimFrontProxyRepo {

    interface Listener {
        /** The bindings changed and the running core may need a rebuild. */
        suspend fun simFrontProxiesChanged()
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    /**
     * The profile the last [resolve] picked, or -1 when the SIM in use was unbound.
     *
     * Moving to an unbound SIM has to drop the front proxy the previous SIM asked for, while an
     * unbound SIM that was never preceded by a bound one keeps the group's own front proxy. Comparing
     * against this is what tells the two apart, so the state only ever moves forward with a [resolve].
     */
    @Volatile
    private var applied: Long = -1L

    private val dao get() = SagerDatabase.simFrontProxyDao

    fun addListener(listener: Listener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun forgetApplied() {
        applied = -1L
    }

    private suspend fun notifyChanged() {
        for (listener in listeners) {
            try {
                listener.simFrontProxiesChanged()
            } catch (e: Throwable) {
                Logs.w(e)
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ reads

    fun all(): List<SimFrontProxyEntity> = dao.all()

    fun get(id: Long): SimFrontProxyEntity? = if (id > 0) dao.getById(id) else null

    /**
     * The front proxy the current SIM asks for, or -1 when no binding matches.
     *
     * A row with a carrier only matches when the active subscription reports the same MCC+MNC; a
     * row without one matches any operator of its slot. A carrier match beats a slot-only one, so a
     * "SIM 1 + 46000" row wins over a "SIM 1 + any" row.
     */
    fun resolve(slot: Int, carrier: String?): Long {
        val bound = bindingFor(slot, carrier)
        applied = bound
        return bound
    }

    /**
     * Whether an unbound SIM should drop the front proxy rather than keep the group's own one.
     *
     * True only when the SIM in use changed from a bound one to an unbound one: a device that never
     * had a match keeps whatever the group configures, which is what a user who never set a binding
     * expects.
     */
    fun shouldClearForUnbound(): Boolean = applied > 0L

    /** The profile of the row matching [slot] / [carrier], or -1. Does not touch [applied]. */
    fun bindingFor(slot: Int, carrier: String?): Long {
        if (!SimFrontProxyEntity.validSlot(slot)) return -1L
        val candidates = dao.bySlot(slot)
        if (candidates.isEmpty()) return -1L
        val normalized = carrier.orEmpty().trim()
        val exact = candidates.firstOrNull { it.carrier.isNotEmpty() && it.carrier == normalized }
        return (exact ?: candidates.firstOrNull { it.carrier.isEmpty() })?.profileId ?: -1L
    }

    // ------------------------------------------------------------------------------------------------ writes

    /** Adds a binding (or replaces the one of the same slot + carrier) and returns its id. */
    suspend fun put(binding: SimFrontProxyEntity): Long {
        val id = SagerDatabase.instance.runInTransaction<Long> {
            val existing = dao.all().firstOrNull {
                it.slot == binding.slot && it.carrier == binding.carrier
            }
            if (existing != null) {
                binding.id = existing.id
                binding.displayOrder = existing.displayOrder
                dao.upsert(binding)
                existing.id
            } else {
                binding.id = 0L
                binding.displayOrder = dao.nextDisplayOrder()
                val assigned = dao.insert(binding)
                binding.id = assigned
                assigned
            }
        }
        notifyChanged()
        return id
    }

    suspend fun delete(id: Long) {
        dao.deleteById(id)
        notifyChanged()
    }

    /** SetSimFrontProxyOrder: [ids] become the rows 0..n-1; rows missing from [ids] follow in their order. */
    suspend fun setDisplayOrder(ids: List<Long>) {
        SagerDatabase.instance.runInTransaction {
            val existing = dao.all().map { it.id }
            val ordered = ids.distinct().filter { it in existing } + existing.filter { it !in ids }
            ordered.forEachIndexed { index, id -> dao.setDisplayOrder(id, index.toLong()) }
        }
        notifyChanged()
    }

    /** Clears every binding. */
    suspend fun reset() {
        dao.reset()
        notifyChanged()
    }
}

/** Cached root of the application for callers outside an Activity. */
private val app get() = SagerNet.application
