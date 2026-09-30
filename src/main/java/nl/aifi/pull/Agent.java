package nl.aifi.pull;

import java.nio.file.Path;
import java.util.logging.Logger;

/** Wires the parts together: KOS listener → job spool → scheduler → puller → destination. */
public final class Agent implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(Agent.class.getName());

    private final Config cfg;
    private JobStore store;
    private Scheduler scheduler;
    private KosScp scp;

    public Agent(Config cfg) {
        this.cfg = cfg;
    }

    public void start() throws Exception {
        store = new JobStore(Path.of(cfg.spool.dir));
        store.lockExclusive();
        store.cleanTransient();
        DicomWebClient source = new DicomWebClient(cfg.source, store);
        scheduler = new Scheduler(cfg, store, new Puller(cfg, store, source));
        scp = new KosScp(cfg.listener, cfg.spool.minFreeMb * 1024 * 1024, store, scheduler);
        scheduler.start();
        scp.start();
        StringBuilder routes = new StringBuilder();
        for (Config.Destination d : cfg.destinations) {
            routes.append(routes.length() == 0 ? "" : ", ").append(d.route).append(" -> ").append(d.name)
                  .append(" (").append(d.aeTitle).append('@').append(d.host).append(':').append(d.port).append(')');
        }
        LOG.info("Retrieving through " + source.base() + "; routes: " + routes + "; spool " + store.root());
    }

    public JobStore store() { return store; }

    @Override
    public void close() {
        if (scp != null) scp.stop();
        if (scheduler != null) scheduler.stop();
        if (store != null) {
            try { store.close(); } catch (Exception ignore) { /* process exits */ }
        }
    }
}
