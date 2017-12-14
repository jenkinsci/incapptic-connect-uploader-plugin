package com.incapptic.plugins.connect;

import com.squareup.okhttp.*;
import com.sun.org.apache.xpath.internal.operations.Bool;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.ProxyConfiguration;
import hudson.model.*;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import hudson.util.FormValidation;
import jenkins.model.Jenkins;
import jenkins.tasks.SimpleBuildStep;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import javax.annotation.Nonnull;
import java.io.*;
import java.net.Proxy;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
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
    private Boolean useMasterProxy;
    private Boolean verboseLogging;

    // Fields in config.jelly must match the parameter names in the "DataBoundConstructor"
    @DataBoundConstructor
    public ConnectNotifier(String token, String url, Integer appId, String mask, Boolean useMasterProxy, Boolean verboseLogging) {
        this.token = token;
        this.url = url;
        this.appId = appId;
        this.mask = mask;
        this.useMasterProxy = useMasterProxy;
        this.verboseLogging = verboseLogging;
    }

    public String getToken() {
        return token;
    }

    public String getUrl() {
        return url;
    }

    public Integer getAppId() { return appId; }

    public Boolean getUseMasterProxy() {
        return useMasterProxy;
    }

    public Boolean getVerboseLogging() {
        return verboseLogging;
    }

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
        for (Action ac : run.getAllActions()) {
            if (ac instanceof ParametersAction) {
                ParametersAction pac = (ParametersAction) ac;
                for (ParameterValue pav : pac.getParameters()) {
                    if (name != null && name.equals(pav.getName())) {
                        return pav.getValue();
                    }
                }
            }
        }
        return null;
    }

    private OkHttpClient getHttpClient(String host, OutputUtils outputUtil) {
        OkHttpClient okHttpClient = new OkHttpClient();
        Jenkins instance = Jenkins.getInstance();

        if (instance != null) {
            if (getUseMasterProxy()) {
                outputUtil.error("Using Jenkins Master proxy settings");
                ProxyConfiguration proxyConfiguration = instance.proxy;
                if (proxyConfiguration != null) {
                    Boolean verboseLog = getVerboseLogging();
                    outputUtil.verbose(String.format("Proxy test url: %s", proxyConfiguration.getTestUrl()), verboseLog);
                    outputUtil.verbose(String.format("Proxy Encrypted Password: %s", proxyConfiguration.getEncryptedPassword()), verboseLog);
                    //outputUtil.info(String.format("Proxy Password: %s", proxyConfiguration.getPassword()));
                    outputUtil.verbose(String.format("Proxy UserName: %s", proxyConfiguration.getUserName()), verboseLog);
                    outputUtil.verbose(String.format("Proxy No Proxy Patterns: %s", proxyConfiguration.getNoProxyHostPatterns().toString()), verboseLog);
                    outputUtil.verbose(String.format("Proxy Port: %s", String.valueOf(proxyConfiguration.port)), verboseLog);
                    outputUtil.verbose(String.format("Proxy Name: %s", proxyConfiguration.name), verboseLog);
                    outputUtil.verbose(String.format("Proxy No Proxy Host: %s", proxyConfiguration.noProxyHost), verboseLog);
                    outputUtil.verbose(String.format("Proxy Config String: %s", proxyConfiguration.toString()), verboseLog);

                    Proxy proxy = proxyConfiguration.createProxy(host);
                    okHttpClient.setProxy(proxy);
                    outputUtil.info("Proxy connection configured.");
                } else {
                    outputUtil.info("Jenkins Master instance HAS NO PROXY INFORMATION !!!");
                }
            } else {
                outputUtil.info("Ignoring the proxy settings");
            }
        }
        return okHttpClient;
    }

    private boolean validate(@Nonnull Run<?, ?> run, OutputUtils outputUtil) {
        outputUtil.info("Incapptic Connect Jenkins Validation starting... ");
        Result result = run.getResult();
        if (result != null && result.isWorseOrEqualTo(Result.FAILURE)) {
            outputUtil.error("Cannot send artifacts from failed build.");
            return false;
        }
        if (getAppId() == null) {
            outputUtil.error("No appId parameter provided.");
            return false;
        }
        if (StringUtils.isEmpty(getMask())) {
            outputUtil.error("No mask parameter provided.");
            return false;
        }
        if (StringUtils.isEmpty(getToken(run))) {
            outputUtil.error("No token parameter provided.");
            return false;
        }
        if (StringUtils.isEmpty(getUrl())) {
            outputUtil.error("No url parameter provided.");
            return false;
        }
        outputUtil.success("Incapptic Connect Uploader plugin successfully validated AppId, Token, " +
                "URL and Binary ");
        return true;
    }

    private MultipartBuilder getMultipartBuilder(@Nonnull FilePath filePath, @Nonnull TaskListener taskListener, OutputUtils outputUtil) {
        MultipartBuilder multipart = new MultipartBuilder();
        multipart.type(MultipartBuilder.FORM);

        try {
            byte[] bytes;
            FilePath artifact = getArtifact(filePath, getMask(), taskListener.getLogger());
            outputUtil.info(String.format(
                    "Artifact %s being sent to Incapptic Connect. ", artifact.getName()));

            String ident = String.format("artifact-%s", getAppId());
            File tmp = File.createTempFile(ident, "tmp");

            try (OutputStream os = new FileOutputStream(tmp)) {
                artifact.copyTo(os);
            }
            try (InputStream is = new FileInputStream(tmp)) {
                bytes = IOUtils.toByteArray(is);
            }

            RequestBody rb = RequestBody.create(MEDIA_TYPE, bytes);
            multipart.addFormDataPart(ident, artifact.getName(), rb);
            return multipart;
        } catch (MultipleArtifactsException e) {
            outputUtil.error(String.format(
                    "Multiple artifacts found for name [%s].", getMask()));
            return null;
        } catch (ArtifactsNotFoundException e) {
            outputUtil.error(String.format(
                    "No artifacts found for name [%s].", getMask()));
            return null;
        } catch (IOException e) {
            outputUtil.error(String.format(
                    "Could not read attachments for name [%s].", getMask()));
            return null;
        } catch (InterruptedException e) {
            outputUtil.error("Interrupted.");
            return null;
        }
    }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    @Override
    public void perform(@Nonnull Run<?, ?> run, @Nonnull FilePath filePath, @Nonnull Launcher launcher, @Nonnull TaskListener taskListener) throws InterruptedException, IOException {
        final OutputUtils outputUtil = OutputUtils.getLoggerForStream(taskListener.getLogger());
        Boolean verboseLog = getVerboseLogging();
        outputUtil.info("Connect plugin is processing build artifacts ");

        if (!validate(run, outputUtil)) {
            return;
        }
        outputUtil.verbose(String.format("Copying binary to folder: %s ", filePath.absolutize().getRemote()), verboseLog);
        MultipartBuilder multipartBuilder = getMultipartBuilder(filePath, taskListener, outputUtil);
        if (multipartBuilder == null) {
            outputUtil.error("No attachments created.");
            return;
        }

        outputUtil.verbose(String.format("Successfully coppied binary to folder: %s ", filePath.absolutize().getRemote()), verboseLog);

        outputUtil.verbose("Creating upload client ", verboseLog);
        OkHttpClient client = getHttpClient(url, outputUtil);

        outputUtil.verbose("Successfully created upload client ", verboseLog);

        Request.Builder builder = new Request.Builder();
        outputUtil.verbose("Constructing Upload Request ", verboseLog);
        builder.addHeader(TOKEN_HEADER_NAME, getToken());
        builder.url(url);
        builder.post(multipartBuilder.build());
        Request request = builder.build();

        outputUtil.verbose("Successfully Constructed Upload Request ", verboseLog);
        for (String name : request.headers().names()) {
            outputUtil.verbose(String.format("Request Head: %s parsed", name), verboseLog);
            //outputUtil.info(String.format("Request Header with Name: %s and Value: %s", name, request.header(name)));
        }
        outputUtil.verbose(String.format("Request Body: %s", request.body().toString()), verboseLog);

        outputUtil.info("Executing Upload Request ");
        Response response = null;
        try {
            response = client.newCall(request).execute();
        } catch (IOException e) {
            outputUtil.verbose(String.format("SOMETHING WENT WRONG!!! HERE IS THE MESSAGE: %s", e.getMessage()), verboseLog);
            outputUtil.verbose(String.format("SOMETHING WENT WRONG!!! HERE IS THE LOCALIZED MESSAGE: %s", e.getLocalizedMessage()), verboseLog);
            outputUtil.verbose(String.format("SOMETHING WENT WRONG!!! HERE IS THE TOSTRING: %s", e.toString()), verboseLog);
            e.printStackTrace(outputUtil.getPrintStream());
            throw new java.lang.Error("Error executing request to incapptic Connect.");
        }

        if (response == null) {
            outputUtil.error("NULL RESPONSE OBTAINED, RETURNING!!!");
        }

        if (!response.isSuccessful()) {
            if (response.code() < 500) {
                String body = IOUtils.toString(response.body().byteStream(), "UTF-8");
                outputUtil.error(String.format(
                        "Endpoint %s replied with code %d and message [%s].",
                        getUrl(), response.code(), body));
                throw new java.lang.Error("Error while uploading to incapptic Connect");
            } else {
                outputUtil.error(String.format(
                        "Endpoint %s replied with code %d.",
                        getUrl(), response.code()));
                throw new java.lang.Error("Error while uploading to incapptic Connect");
            }
        } else {
            outputUtil.verbose("Successfully Executed Upload Request ", verboseLog);
            outputUtil.success(response.body().string());
            outputUtil.success("All artifacts sent to Connect");
        }
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws IOException, InterruptedException {
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

        for (FilePath child : parent.list()) {
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

    @Extension(ordinal = -1)
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
