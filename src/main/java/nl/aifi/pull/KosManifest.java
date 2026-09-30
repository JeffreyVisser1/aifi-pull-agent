package nl.aifi.pull;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Sequence;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * What a pull request asks for. The KOS starts as the AIFI gateway's trigger to JiveX; JiveX
 * pseudonymizes it with its own profile before routing it here, so the agent relies only on
 * what survives that:
 * <ul>
 *   <li>the StudyInstanceUID (JiveX's pseudonym, the same UID the gateway pool uses);</li>
 *   <li>the route: Key Object Description {@code "AIFI route=...; instances=n"}, else the Series
 *       Description {@code "AIFI route=..."}, else empty (then the agent reads it from the
 *       retrieved instances);</li>
 *   <li>the number of instances: from the description, else counted in the evidence sequence
 *       (the UIDs there may have been re-mapped), else unknown (0).</li>
 * </ul>
 */
public final class KosManifest {

    public final String kosUid;
    public final String studyUid;
    public final String route;
    /** Instances the gateway received for this request; 0 = unknown. */
    public final int expected;
    /** Whether the KOS carries a mark of the AIFI gateway. */
    public final boolean fromAifi;

    private KosManifest(String kosUid, String studyUid, String route, int expected, boolean fromAifi) {
        this.kosUid = kosUid;
        this.studyUid = studyUid;
        this.route = route;
        this.expected = expected;
        this.fromAifi = fromAifi;
    }

    /** @throws IllegalArgumentException when the object is not a usable pull request */
    public static KosManifest parse(Attributes kos) {
        if (!UID.KeyObjectSelectionDocumentStorage.equals(kos.getString(Tag.SOPClassUID))) {
            throw new IllegalArgumentException("not a Key Object Selection document");
        }
        String kosUid = kos.getString(Tag.SOPInstanceUID);
        String studyUid = kos.getString(Tag.StudyInstanceUID);
        if (!isUid(kosUid) || !isUid(studyUid)) throw new IllegalArgumentException("missing or invalid Study/SOP Instance UID");

        Map<String, String> text = description(kos);
        String route = text.getOrDefault("route", "");
        String seriesDescription = kos.getString(Tag.SeriesDescription, "");
        if (route.isEmpty() && seriesDescription.startsWith("AIFI route=")) {
            route = seriesDescription.substring("AIFI route=".length()).trim();
        }
        if (!route.isEmpty() && !route.matches("[a-z0-9][a-z0-9-]{0,31}")) {
            throw new IllegalArgumentException("invalid route name '" + route + "'");
        }
        int expected = 0;
        try {
            expected = Integer.parseInt(text.getOrDefault("instances", "0"));
        } catch (NumberFormatException ignore) { /* counted below */ }
        if (expected <= 0) expected = evidenceCount(kos);
        boolean fromAifi = !text.isEmpty() || seriesDescription.startsWith("AIFI ")
                || kos.getString(Tag.Manufacturer, "").startsWith("AIFI ");
        return new KosManifest(kosUid, studyUid, route, Math.max(0, expected), fromAifi);
    }

    /** Key/value pairs of the Key Object Description text written by the gateway. */
    static Map<String, String> description(Attributes kos) {
        Map<String, String> out = new LinkedHashMap<>();
        Sequence content = kos.getSequence(Tag.ContentSequence);
        if (content == null) return out;
        for (Attributes item : content) {
            if (!"TEXT".equals(item.getString(Tag.ValueType))) continue;
            String text = item.getString(Tag.TextValue, "");
            if (!text.startsWith("AIFI ")) continue;
            for (String part : text.substring(5).split(";")) {
                String[] kv = part.trim().split("=", 2);
                if (kv.length == 2) out.put(kv[0].trim(), kv[1].trim());
            }
        }
        return out;
    }

    /** Distinct instances referenced in the Current Requested Procedure Evidence Sequence. */
    static int evidenceCount(Attributes kos) {
        Set<String> sops = new LinkedHashSet<>();
        Sequence studies = kos.getSequence(Tag.CurrentRequestedProcedureEvidenceSequence);
        if (studies == null) return 0;
        for (Attributes st : studies) {
            Sequence series = st.getSequence(Tag.ReferencedSeriesSequence);
            if (series == null) continue;
            for (Attributes se : series) {
                Sequence refs = se.getSequence(Tag.ReferencedSOPSequence);
                if (refs == null) continue;
                for (Attributes r : refs) {
                    String sop = r.getString(Tag.ReferencedSOPInstanceUID);
                    if (sop != null) sops.add(sop);
                }
            }
        }
        return sops.size();
    }

    public static boolean isUid(String uid) {
        return uid != null && uid.length() <= 64 && uid.matches("[0-9]+(\\.[0-9]+)*");
    }
}
