package com.incapptic.plugins.appconnectplugin;

import com.squareup.okhttp.*;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.*;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import hudson.util.FormValidation;
import org.apache.commons.io.IOUtils;
import org.apache.commons.validator.routines.UrlValidator;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import java.io.*;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * @author Tomasz Jurkiewicz
 */
public class AppConnectNotifier extends Recorder implements Serializable {
    private static final long serialVersionUID = 1L;

    public static final MediaType MEDIA_TYPE = MediaType.parse("application/octet-stream");

    private String token;
    private List<ArtifactConfig> artifactConfigList;

    // Fields in config.jelly must match the parameter names in the "DataBoundConstructor"
    @DataBoundConstructor
    public AppConnectNotifier(String url, String token, List<ArtifactConfig> artifactConfigList) {
        this.token = token;
        this.artifactConfigList = artifactConfigList;
    }

    public String getToken() {
        return token;
    }

    public List<ArtifactConfig> getArtifactConfigList() {
        if (artifactConfigList == null) {
            return new ArrayList<>();
        }
        return artifactConfigList;
    }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }



    @Override
    public boolean perform(AbstractBuild<?,?> build, Launcher launcher, BuildListener listener) throws IOException {
        if (build.getResult().isWorseOrEqualTo(Result.FAILURE)) {
            listener.getLogger().format("Cannot send artifacts from failed build.");
            return true;
        }

        MultipartBuilder multipart = new MultipartBuilder();
        multipart.type(MultipartBuilder.FORM);
        multipart.addFormDataPart("token", token);

        if (getArtifactConfigList().isEmpty()) {
            listener.getLogger().format("No artifacts configured.");
            return true;
        }

        for(ArtifactConfig ac: getArtifactConfigList()) {
            try {
                byte[] bytes;
                FilePath artifact = getArtifact(build, ac.getName(), listener.getLogger());

                listener.getLogger().println(
                        String.format("Artifact %s being sent to Incapptic Appconnect", artifact.getName()));

                File tmp = File.createTempFile("artifact", "tmp");

                try(OutputStream os = new FileOutputStream(tmp)) {
                    artifact.copyTo(os);
                }
                try(InputStream is = new FileInputStream(tmp)) {
                    bytes = IOUtils.toByteArray(is);
                }

                RequestBody rb = RequestBody.create(MEDIA_TYPE, bytes);
                multipart.addFormDataPart("artifact", artifact.getName(), rb);

                Request.Builder builder = new Request.Builder();
                builder.url(ac.getUrl());
                builder.post(multipart.build());

                Request request = builder.build();
                OkHttpClient client = new OkHttpClient();
                Response response = client.newCall(request).execute();

                if(!response.isSuccessful()) {
                    String msg = String.format("Endpoint %s replied with code %d", ac.getUrl(), response.code());
                    throw new AppConnectException(msg);
                }

            } catch (MultipleArtifactsException e) {
                String msg = String.format("Multiple artifacts found for name %s", ac.getName());
                listener.getLogger().println(msg);
                return true;
            } catch (ArtifactsNotFoundException e) {
                String msg = String.format("No artifacts found for name %s", ac.getName());
                listener.getLogger().println(msg);
                return true;
            } catch (InterruptedException e) {
                listener.getLogger().println("Interrupted.");
                return true;
            }

        }

        listener.getLogger().println("Artifacts scheduled in Incapptic Appconnect");
        return true;
    }

    private FilePath getArtifact(AbstractBuild<?, ?> build, String glob, PrintStream logger)
            throws MultipleArtifactsException, ArtifactsNotFoundException, IOException, InterruptedException {
        FilePath workspace = build.getWorkspace();

        PathMatcher matcher = FileSystems.getDefault().getPathMatcher(String.format("glob:%s", glob));
        List<FilePath> artifacts = new ArrayList<>();
        getArtifacts(workspace, "", matcher, artifacts, logger);

        if (artifacts.size() == 0) {
            throw new ArtifactsNotFoundException();
        } else if (artifacts.size() > 1) {
            throw new MultipleArtifactsException();
        } else {
            return artifacts.get(0);
        }

    }

    private void getArtifacts(FilePath parent, String base, PathMatcher matcher, List<FilePath> artifacts, PrintStream logger)
            throws IOException, InterruptedException {

        for(FilePath child: parent.list()) {
            if (child.isDirectory()) {
                getArtifacts(child, String.format("%s/%s", base, child.getName()), matcher, artifacts, logger);
            } else {
                Path path = Paths.get(base, child.getName());
                if (matcher.matches(path)) {
                    logger.println(String.format("Path %s matches.", path.toString()));
                    artifacts.add(child);
                }
            }
        }
    }

    @Extension(ordinal=-1)
    public static final class DescriptorImpl
            extends BuildStepDescriptor<Publisher> { // Publisher because Notifiers are a type of publisher

        public static final UrlValidator URL_VALIDATOR = new UrlValidator(new String[] {"https", "http"});

        public DescriptorImpl() {
            super(AppConnectNotifier.class);
        }

        @Override
        public String getDisplayName() {
            return "Incapptic AppConnect Publisher";
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        public FormValidation doCheckUrl(@QueryParameter String value) {
            if (!URL_VALIDATOR.isValid(value)) {
                return FormValidation.error("Invalid URL");

            }
            return FormValidation.ok();
        }
    }

    public static class ArtifactConfig extends AbstractDescribableImpl<ArtifactConfig> implements Serializable {
        private static final long serialVersionUID = 1L;

        private String name;
        private String url;

        @DataBoundConstructor
        public ArtifactConfig(String name, String url) {
            this.name = name;
            this.url = url;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        @Extension
        public static class DescriptorImpl extends Descriptor<ArtifactConfig> {
            @Override
            public String getDisplayName() { return ""; }
        }
    }
}

