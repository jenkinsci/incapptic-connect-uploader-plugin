package com.incapptic.plugins.connect;

import com.squareup.okhttp.*;
import hudson.Extension;
import hudson.ExtensionPoint;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.*;
import hudson.tasks.*;
import hudson.util.FormValidation;
import jenkins.tasks.SimpleBuildStep;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.validator.routines.UrlValidator;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import sun.security.ssl.SSLSocketFactoryImpl;

import javax.annotation.Nonnull;
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
public class ConnectNotifier extends Recorder implements Serializable, SimpleBuildStep {
    public static final String TOKEN_HEADER_NAME = "X-Connect-Token";
    private static final long serialVersionUID = 1L;

    public static final MediaType MEDIA_TYPE = MediaType.parse("application/octet-stream");

    private String token;
    private String url;
    private Integer appId;
    private String mask;

    // Fields in config.jelly must match the parameter names in the "DataBoundConstructor"
    @DataBoundConstructor
    public ConnectNotifier(String token, String url, Integer appId, String mask) {
        this.token = token;
        this.url = url;
        this.appId = appId;
        this.mask = mask;
    }

    public String getToken() {
        return token;
    }

    public String getUrl() {
        return url;
    }

    public Integer getAppId() { return appId; }

    public String getMask() { return mask; }

    private String getToken(@Nonnull Run<?, ?> run) {
        if (StringUtils.isEmpty(getToken())) {
            Object tokenValue = getParameterValue(run, "token");
            if (tokenValue != null) {
                token = tokenValue.toString();
            }
        }
        return token;
    }

    private Object getParameterValue(@Nonnull Run<?, ?> run, String name) {
        for(Action ac: run.getAllActions()) {
            if (ac instanceof ParametersAction) {
                ParametersAction pac = (ParametersAction) ac;
                for(ParameterValue pav: pac.getParameters()) {
                    if (name != null && name.equals(pav.getName())) {
                        return pav.getValue();
                    }
                }
            }
        }
        return null;
    }


    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    @Override
    public void perform(@Nonnull Run<?, ?> run, @Nonnull FilePath filePath, @Nonnull Launcher launcher, @Nonnull TaskListener taskListener) throws InterruptedException, IOException {
        OutputUtils outputUtil = OutputUtils.getLoggerForStream(taskListener.getLogger());

        outputUtil.info("-----* Connect plugin is processing build artifacts *-----");

        Result result = run.getResult();
        if (result == null) {
            return;
        }
        if (result.isWorseOrEqualTo(Result.FAILURE)) {
            outputUtil.error("Cannot send artifacts from failed build.");
            return;
        }

        if (getAppId() == null) {
            outputUtil.error("No appId parameter provided.");
            return;
        }
        if (StringUtils.isEmpty(getMask())) {
            outputUtil.error("No mask parameter provided.");
            return;
        }
        if (StringUtils.isEmpty(getToken(run))) {
            outputUtil.error("No token parameter provided.");
            return;
        }
        if (StringUtils.isEmpty(getUrl())) {
            outputUtil.error("No url parameter provided.");
            return;
        }

        MultipartBuilder multipart = new MultipartBuilder();
        multipart.type(MultipartBuilder.FORM);

        try {
            byte[] bytes;
            FilePath artifact = getArtifact(filePath, getMask(), taskListener.getLogger());
            outputUtil.info(String.format(
                    "Artifact %s being sent to Incapptic Connect.", artifact.getName()));

            String ident = String.format("artifact-%s", getAppId());
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
                    "Multiple artifacts found for name [%s].", getMask()));
            return;
        } catch (ArtifactsNotFoundException e) {
            outputUtil.error(String.format(
                    "No artifacts found for name [%s].", getMask()));
            return;
        } catch (InterruptedException e) {
            outputUtil.error("Interrupted.");
            return;
        }

        Request.Builder builder = new Request.Builder();
        builder.addHeader(TOKEN_HEADER_NAME, getToken());
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
                        getUrl(), response.code(), body));
            } else {
                outputUtil.error(String.format(
                        "Endpoint %s replied with code %d.",
                        getUrl(), response.code()));
            }
        } else {
            outputUtil.success("All artifacts sent to Connect");
        }
    }


    @Override
    public boolean perform(AbstractBuild<?,?> build, Launcher launcher, BuildListener listener) throws IOException, InterruptedException {
        FilePath workspace = build.getWorkspace();
        if (workspace == null) {
            return false;
        }
        perform(build, workspace, launcher, listener);
        return true;
    }

    private FilePath getArtifact(FilePath workspace, String glob, PrintStream logger)
            throws MultipleArtifactsException, ArtifactsNotFoundException, IOException, InterruptedException {
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
    @Symbol("uploadToIncappticConnect")
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

}

