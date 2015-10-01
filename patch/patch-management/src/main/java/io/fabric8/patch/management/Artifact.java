package io.fabric8.patch.management;

public class Artifact {

    private final String groupId;
    private final String artifactId;
    private final String version;
    private final String type;
    private final String classifier;

    public Artifact(String groupId, String artifactId, String version, String type, String classifier) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
        this.type = type;
        this.classifier = classifier;
    }

    public static boolean isSameButVersion(Artifact a1, Artifact a2) {
        return a1.getGroupId().equals(a2.getGroupId())
                && a1.getArtifactId().equals(a2.getArtifactId())
                && a1.hasClassifier() == a2.hasClassifier()
                && (!a1.hasClassifier() || a1.getClassifier().equals(a2.getClassifier()))
                && a1.getType().equals(a2.getType());
    }

    public String getGroupId() {
        return groupId;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public String getVersion() {
        return version;
    }

    public String getType() {
        return type;
    }

    public String getClassifier() {
        return classifier;
    }

    public boolean hasClassifier() {
        return classifier != null;
    }

    public String getPath() {
        return groupId.replace('.', '/')
                + '/'
                + artifactId
                + '/'
                + version
                + '/'
                + artifactId
                + (classifier != null ? "-" + classifier : "")
                + '-'
                + version
                + '.'
                + type;
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(groupId)
                .append(":")
                .append(artifactId)
                .append(":")
                .append(version);
        if (!"jar".equals(type) || classifier != null) {
            sb.append(":").append(type);
            if (classifier != null) {
                sb.append(":").append(classifier);
            }
        }
        return sb.toString();
    }

}
