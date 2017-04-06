package com.incapptic.plugins.connect;

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
import org.apache.commons.lang.StringUtils;
import org.apache.commons.validator.routines.UrlValidator;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import sun.security.ssl.SSLSocketFactoryImpl;

import javax.net.ssl.*;
import java.io.*;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * @author Tomasz Jurkiewicz
 */
public class ConnectNotifier extends Recorder implements Serializable {
    public static final String TOKEN_HEADER_NAME = "X-Connect-Token";
    private static final long serialVersionUID = 1L;

    public static final MediaType MEDIA_TYPE = MediaType.parse("application/octet-stream");

    private String token;
    private String url;

    private List<ArtifactConfig> artifactConfigList;

    // Fields in config.jelly must match the parameter names in the "DataBoundConstructor"
    @DataBoundConstructor
    public ConnectNotifier(String token, String url, List<ArtifactConfig> artifactConfigList) {
        this.token = token;
        this.url = url;
        this.artifactConfigList = artifactConfigList;
    }

    public String getToken() {
        return token;
    }

    public String getUrl() {
        return url;
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
        OutputUtils outputUtil = OutputUtils.getLoggerForStream(listener.getLogger());

        outputUtil.info("-----* Connect plugin is processing build artifacts *-----");


        if (build.getResult().isWorseOrEqualTo(Result.FAILURE)) {
            outputUtil.error("Cannot send artifacts from failed build.");
            return true;
        }

        MultipartBuilder multipart = new MultipartBuilder();
        multipart.type(MultipartBuilder.FORM);

        if (getArtifactConfigList().isEmpty()) {
            outputUtil.info("No artifacts configured.");
            return true;
        }

        for(ArtifactConfig ac: getArtifactConfigList()) {
            try {
                byte[] bytes;
                FilePath artifact = getArtifact(build, ac.getName(), listener.getLogger());
                outputUtil.info(String.format(
                        "Artifact %s being sent to Incapptic Connect.", artifact.getName()));

                String ident = String.format("artifact-%s", ac.getAppId());
                File tmp = File.createTempFile(ident, "tmp");

                try(OutputStream os = new FileOutputStream(tmp)) {
                    artifact.copyTo(os);
                }
                try(InputStream is = new FileInputStream(tmp)) {
                    bytes = IOUtils.toByteArray(is);
                }

                RequestBody rb = RequestBody.create(MEDIA_TYPE, bytes);
                multipart.addFormDataPart(ident, artifact.getName(), rb);


            } catch (MultipleArtifactsException e) {
                outputUtil.error(String.format(
                        "Multiple artifacts found for name [%s].", ac.getName()));
            } catch (ArtifactsNotFoundException e) {
                outputUtil.error(String.format(
                        "No artifacts found for name [%s].", ac.getName()));
            } catch (InterruptedException e) {
                outputUtil.error("Interrupted.");
            }

        }

        Request.Builder builder = new Request.Builder();
        builder.addHeader(TOKEN_HEADER_NAME, token);
        builder.url(url);
        builder.post(multipart.build());

        Request request = builder.build();
        OkHttpClient client = new OkHttpClient();

        Response response = client.newCall(request).execute();

        if(!response.isSuccessful()) {
            if (response.code() < 500) {
                String body = IOUtils.toString(response.body().byteStream(), "UTF-8");
                outputUtil.error(String.format(
                        "Endpoint %s replied with code %d and message [%s].",
                        url, response.code(), body));
            } else {
                outputUtil.error(String.format(
                        "Endpoint %s replied with code %d.",
                        url, response.code()));
            }
        } else {
            outputUtil.success("All artifacts sent to Connect");
        }

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
                    artifacts.add(child);
                }
            }
        }
    }

    @Extension(ordinal=-1)
    public static final class DescriptorImpl
            extends BuildStepDescriptor<Publisher> { // Publisher because Notifiers are a type of publisher

        public DescriptorImpl() {
            super(ConnectNotifier.class);
        }

        @Override
        public String getDisplayName() {
            return "Incapptic Connect Publisher";
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        public FormValidation doCheckToken(@QueryParameter String token) {
            if (StringUtils.isEmpty(token)) {
                return FormValidation.error("Empty token");
            }
            return FormValidation.ok();
        }
    }

    public static class ArtifactConfig extends AbstractDescribableImpl<ArtifactConfig> implements Serializable {
        private static final long serialVersionUID = 1L;

        private String name;
        private String appId;

        @DataBoundConstructor
        public ArtifactConfig(String name, String appId) {
            this.name = name;
            this.appId = appId;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getAppId() {
            return appId;
        }

        public void setAppId(String appId) {
            this.appId = appId;
        }

        @Extension
        public static class DescriptorImpl extends Descriptor<ArtifactConfig> {
            @Override
            public String getDisplayName() { return ""; }

            public static final UrlValidator URL_VALIDATOR = new UrlValidator(new String[] {"https", "http"});

            public FormValidation doCheckUrl(@QueryParameter String value) {
                if (!URL_VALIDATOR.isValid(value)) {
                    return FormValidation.error("Invalid URL");
                }
                return FormValidation.ok();
            }
        }
    }
}

