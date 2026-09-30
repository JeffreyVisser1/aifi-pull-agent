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
 * What a "study ready" KOS asks for: the study, the route, and per series the SOP instances
 * listed in the Current Requested Procedure Evidence Sequence. The route comes from the Key
 * Object Description text the AIFI gateway writes ({@code "AIFI route=ai-thorax; ..."}).
 */
public final class KosManifest {

    public final String kosUid;
    public final String studyUid;
    public final String route;
    /** Series UID → SOP instance UIDs, in KOS order. */
    public final Map<String, Set<String>> series;

    private KosManifest(String kosUid, String studyUid, String route, Map<String, Set<String>> series) {
        this.kosUid = kosUid;
        this.studyUid = studyUid;
        this.route = route;
        this.series = series;
    }

    public int instanceCount() {
        return series.values().stream().mapToInt(Set::size).sum();
    }

    /** @throws IllegalArgumentException when the object is not a usable pull request */
    public static KosManifest parse(Attributes kos) {
        if (!UID.KeyObjectSelectionDocumentStorage.equals(kos.getString(Tag.SOPClassUID))) {
            throw new IllegalArgumentException("not a Key Object Selection document");
        }
        String kosUid = kos.getString(Tag.SOPInstanceUID);
        String studyUid = kos.getString(Tag.StudyInstanceUID);
        if (!isUid(kosUid) || !isUid(studyUid)) throw new IllegalArgumentException("missing or invalid Study/SOP Instance UID");
        Map<String, Set<String>> series = new LinkedHashMap<>();
        Sequence studies = kos.getSequence(Tag.CurrentRequestedProcedureEvidenceSequence);
        if (studies != null) {
            for (Attributes st : studies) {
                if (!studyUid.equals(st.getString(Tag.StudyInstanceUID))) {
                    throw new IllegalArgumentException("the evidence references another study than the KOS itself");
                }
                Sequence ser = st.getSequence(Tag.ReferencedSeriesSequence);
                if (ser == null) continue;
                for (Attributes se : ser) {
                    String seriesUid = se.getString(Tag.SeriesInstanceUID);
                    if (!isUid(seriesUid)) throw new IllegalArgumentException("invalid Series Instance UID in the evidence");
                    Set<String> sops = series.computeIfAbsent(seriesUid, k -> new LinkedHashSet<>());
                    Sequence refs = se.getSequence(Tag.ReferencedSOPSequence);
                    if (refs == null) continue;
                    for (Attributes r : refs) {
                        String sop = r.getString(Tag.ReferencedSOPInstanceUID);
                        if (!isUid(sop)) throw new IllegalArgumentException("invalid SOP Instance UID in the evidence");
                        sops.add(sop);
                    }
                }
            }
        }
        series.values().removeIf(Set::isEmpty);
        if (series.isEmpty()) throw new IllegalArgumentException("the KOS references no instances");
        return new KosManifest(kosUid, studyUid, route(kos), series);
    }

    /** The route named in the Key Object Description, or "" when there is none. */
    static String route(Attributes kos) {
        Sequence content = kos.getSequence(Tag.ContentSequence);
        if (content == null) return "";
        for (Attributes item : content) {
            if (!"TEXT".equals(item.getString(Tag.ValueType))) continue;
            String text = item.getString(Tag.TextValue, "");
            if (!text.startsWith("AIFI ")) continue;
            for (String part : text.substring(5).split(";")) {
                String[] kv = part.trim().split("=", 2);
                if (kv.length == 2 && kv[0].trim().equals("route")) return kv[1].trim();
            }
        }
        return "";
    }

    public static boolean isUid(String uid) {
        return uid != null && uid.length() <= 64 && uid.matches("[0-9]+(\\.[0-9]+)*");
    }
}
