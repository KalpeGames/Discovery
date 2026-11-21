package cc.serverlist.featured.compat;

/** 1.21.8 has no input event objects; keyPressed's old signature wraps its values in this. */
public record KeyEvent(int key, int scancode, int modifiers) {
}
