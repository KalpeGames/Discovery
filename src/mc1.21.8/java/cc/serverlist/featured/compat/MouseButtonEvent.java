package cc.serverlist.featured.compat;

/** 1.21.8 has no input event objects; the handlers' old signatures wrap their values in this. */
public record MouseButtonEvent(double x, double y, int button) {
}
