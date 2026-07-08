package ru.asop.terminal.repository

import org.springframework.data.repository.reactive.ReactiveCrudRepository
import org.springframework.stereotype.Repository
import ru.asop.terminal.model.TerminalEntity
import java.util.UUID

@Repository
interface TerminalRepository : ReactiveCrudRepository<TerminalEntity, UUID>
