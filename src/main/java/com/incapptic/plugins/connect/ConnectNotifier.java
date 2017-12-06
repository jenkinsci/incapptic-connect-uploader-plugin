package com.incapptic.plugins.connect;

import com.squareup.okhttp.*;
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

    private OkHttpClient getHttpClient(String host, OutputUtils outputUtil) {
        OkHttpClient okHttpClient = new OkHttpClient();
        Jenkins instance = Jenkins.getInstance();

        if (instance != null) {
            ProxyConfiguration proxyConfiguration = instance.proxy;
            if (proxyConfiguration != null) {
                Proxy proxy = proxyConfiguration.createProxy(host);
                okHttpClient.setProxy(proxy);
                outputUtil.info("Proxy connection configured.");
            }
        }
        return okHttpClient;
    }

    private boolean validate(@Nonnull Run<?, ?> run, OutputUtils outputUtil) {
        outputUtil.info("### Incapptic Connect Jenkins Validation starting... ");
        Result result = run.getResult();
        if (result != null && result.isWorseOrEqualTo(Result.FAILURE)) {
            outputUtil.error("@@@ Cannot send artifacts from failed build.");
            return false;
        }
        if (getAppId() == null) {
            outputUtil.error("@@@ No appId parameter provided.");
            return false;
        }
        if (StringUtils.isEmpty(getMask())) {
            outputUtil.error("@@@ No mask parameter provided.");
            return false;
        }
        if (StringUtils.isEmpty(getToken(run))) {
            outputUtil.error("@@@ No token parameter provided.");
            return false;
        }
        if (StringUtils.isEmpty(getUrl())) {
            outputUtil.error("@@@ No url parameter provided.");
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
                    "### Artifact %s being sent to Incapptic Connect. ", artifact.getName()));

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
            outputUtil.error("@@@ Interrupted.");
            return null;
        }
    }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    @Override
    public void perform(@Nonnull Run<?, ?> run, @Nonnull FilePath filePath, @Nonnull Launcher launcher, @Nonnull TaskListener taskListener) throws InterruptedException, IOException {
        OutputUtils outputUtil = OutputUtils.getLoggerForStream(taskListener.getLogger());
        outputUtil.info("### Connect plugin is processing build artifacts ");

        if (!validate(run, outputUtil)) {
            return;
        }
        outputUtil.info(String.format("### Copying binary to folder: %s ", filePath.absolutize().getRemote()));
        MultipartBuilder multipartBuilder = getMultipartBuilder(filePath, taskListener, outputUtil);
        if (multipartBuilder == null) {
            outputUtil.error("@@@ No attachments created.");
            return;
        }

        outputUtil.success(String.format("Successfully binary to copied to folder: %s ", filePath.absolutize().getRemote()));

        outputUtil.info("### Creating upload client ");
        OkHttpClient client = getHttpClient(url, outputUtil);

        outputUtil.success("Successfully created upload client ");

        Request.Builder builder = new Request.Builder();
        outputUtil.info("### Constructing Upload Request ");
        builder.addHeader(TOKEN_HEADER_NAME, getToken());
        builder.url(url);
        builder.post(multipartBuilder.build());
        Request request = builder.build();
        outputUtil.success("Successfully Constructed Upload Request ");

        outputUtil.info("### Executing Upload Request ");
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
            outputUtil.success("Successfully Executed Upload Request ");
            outputUtil.success(response.body().string());
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
