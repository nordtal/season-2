package eu.nordtal.s2.steward.web;

import java.util.List;

/** The records the routes of this package answer and read, for the generated TypeScript types. */
public final class WebWire {

    /** Every record a route here answers or reads at its top level. */
    public static final List<Class<?>> ROOTS = List.of(
            Profile.Me.class,
            PushEndpoints.WebPushPublicKey.class,
            PushEndpoints.PushDevice.class,
            AlertRoutes.Alerts.class,
            AlertRoutes.AlertPreference.class);

    private WebWire() {}
}
