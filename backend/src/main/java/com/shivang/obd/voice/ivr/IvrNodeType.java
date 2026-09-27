package com.shivang.obd.voice.ivr;

/**
 * What a node does when the caller arrives at it (VB-6F).
 * <p>
 * Deliberately only two kinds. The objective is reusable finite navigation, not
 * a generalized workflow engine, so every additional type would need a product
 * requirement to justify it.
 */
public enum IvrNodeType {

    /**
     * Asks a question: plays its prompt, waits {@code inputWaitSeconds} for one
     * DTMF digit, and follows the transition matching that digit. A digit with
     * no transition is an invalid input.
     * <p>
     * May carry zero or more transitions. A MENU with none is a dead end the
     * validator rejects, because a caller who presses anything would loop on
     * the invalid-input prompt until the retries are exhausted.
     */
    MENU,

    /**
     * Ends the flow. Must carry a {@link IvrTerminalAction} and must not carry
     * transitions or a prompt — it is reached, not answered.
     */
    TERMINAL
}
