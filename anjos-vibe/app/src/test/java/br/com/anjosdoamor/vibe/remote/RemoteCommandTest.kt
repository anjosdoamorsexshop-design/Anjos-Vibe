package br.com.anjosdoamor.vibe.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteCommandTest {

    // ---- parse -----------------------------------------------------------

    @Test
    fun modoValido() {
        assertEquals(RemoteCommand.Mode(3), RemoteCommand.parse("mode", 3L))
        assertEquals(RemoteCommand.Mode(1), RemoteCommand.parse("mode", 1L))
        assertEquals(RemoteCommand.Mode(9), RemoteCommand.parse("mode", 9L))
    }

    @Test
    fun modoForaDaFaixa() {
        assertNull(RemoteCommand.parse("mode", 0L))
        assertNull(RemoteCommand.parse("mode", 10L))
        assertNull(RemoteCommand.parse("mode", null))
        assertNull(RemoteCommand.parse("mode", "3"))
    }

    @Test
    fun numeroQueChegaComoDouble() {
        // O Firebase entrega Long ou Double conforme o valor gravado
        assertEquals(RemoteCommand.Mode(2), RemoteCommand.parse("mode", 2.0))
        assertNull(RemoteCommand.parse("mode", 2.5))
    }

    @Test
    fun nivelDoDesenho() {
        assertEquals(RemoteCommand.Level(0), RemoteCommand.parse("level", 0L))
        assertEquals(RemoteCommand.Level(3), RemoteCommand.parse("level", 3L))
        assertNull(RemoteCommand.parse("level", 4L))
        assertNull(RemoteCommand.parse("level", -1L))
    }

    @Test
    fun padrao() {
        assertEquals(RemoteCommand.PlayPattern("onda"), RemoteCommand.parse("pattern", "onda"))
        assertNull(RemoteCommand.parse("pattern", ""))
        assertNull(RemoteCommand.parse("pattern", 5L))
        assertNull(RemoteCommand.parse("pattern", "x".repeat(65)))
    }

    @Test
    fun parar() {
        assertEquals(RemoteCommand.Stop, RemoteCommand.parse("stop", null))
    }

    @Test
    fun tipoDesconhecido() {
        assertNull(RemoteCommand.parse("xyz", 1L))
        assertNull(RemoteCommand.parse(null, 1L))
    }

    // ---- gate ------------------------------------------------------------

    @Test
    fun aceitaSeqCrescente() {
        val gate = RemoteGate()
        assertTrue(gate.accept(1, 0))
        assertTrue(gate.accept(2, 100))
        assertTrue(gate.accept(10, 200))
    }

    @Test
    fun rejeitaSeqRepetidoOuMenor() {
        val gate = RemoteGate()
        assertTrue(gate.accept(5, 0))
        assertFalse(gate.accept(5, 100))
        assertFalse(gate.accept(4, 200))
        assertTrue(gate.accept(6, 300))
    }

    @Test
    fun limitaVinteComandosPorSegundo() {
        val gate = RemoteGate(maxPerSecond = 20)
        for (i in 1..20) assertTrue("comando $i", gate.accept(i.toLong(), 1000L + i))
        assertFalse(gate.accept(21, 1500))
        // Um segundo depois do primeiro, a janela reabre
        assertTrue(gate.accept(22, 2002))
    }

    @Test
    fun comandoRecusadoPeloLimiteNaoTravaOsSeguintes() {
        val gate = RemoteGate(maxPerSecond = 2)
        assertTrue(gate.accept(1, 0))
        assertTrue(gate.accept(2, 10))
        assertFalse(gate.accept(3, 20))
        assertTrue(gate.accept(4, 1100))
    }

    @Test
    fun resetVoltaAAceitarDoComeco() {
        val gate = RemoteGate()
        assertTrue(gate.accept(50, 0))
        gate.reset()
        assertTrue(gate.accept(1, 10))
    }
}
