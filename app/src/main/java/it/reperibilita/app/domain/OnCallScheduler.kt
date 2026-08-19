package it.reperibilita.app.domain

import it.reperibilita.app.model.ShiftEntry
import java.time.LocalDateTime

/** Logica pura di selezione del turno attivo: nessuna dipendenza Android, facilmente testabile. */
class OnCallScheduler {

    fun findActiveShift(shifts: List<ShiftEntry>, at: LocalDateTime): ShiftEntry? {
        val active = shifts.filter { it.isActiveAt(at) }
        if (active.size > 1) {
            // Sovrapposizione nel calendario: vince il turno con inizio piu' recente, per non
            // lasciare l'automazione bloccata su un'ambiguita' nel file sorgente.
            return active.maxByOrNull { it.startDateTime }
        }
        return active.firstOrNull()
    }

    fun findNextUpcomingShift(shifts: List<ShiftEntry>, after: LocalDateTime): ShiftEntry? =
        shifts.filter { it.startDateTime.isAfter(after) }.minByOrNull { it.startDateTime }
}
