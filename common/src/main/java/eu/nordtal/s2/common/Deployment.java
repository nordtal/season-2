package eu.nordtal.s2.common;

/** The one compose deployment of this network, as every process that drives Docker names it. */
public final class Deployment {

    /** The compose project every container belongs to unless {@code COMPOSE_PROJECT_NAME} says otherwise. */
    public static final String PROJECT = "nordtal-s2";

    private Deployment() {}
}
