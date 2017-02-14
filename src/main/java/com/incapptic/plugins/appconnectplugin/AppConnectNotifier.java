package com.incapptic.plugins.appconnectplugin;

import com.squareup.okhttp.*;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.*;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import hudson.util.FormValidation;
import org.apache.commons.validator.routines.EmailValidator;
import org.apache.commons.validator.routines.UrlValidator;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import java.io.IOException;
import java.util.List;

/**
 * @author Tomasz Jurkiewicz
 */
public class AppConnectNotifier extends Notifier  {

    private final String url;
    private final String appId;
    private final String email;
    private final String token;

    // Fields in config.jelly must match the parameter names in the "DataBoundConstructor"
    @DataBoundConstructor
    public AppConnectNotifier(String url, String appId, String email, String token) {
        this.url = url;
        this.appId = appId;
        this.email = email;
        this.token = token;
    }

    public String getUrl() {
        return url;
    }

    public String getAppId() {
        return appId;
    }

    public String getEmail() {
        return email;
    }

    public String getToken() {
        return token;
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


        List<? extends Run<?, ?>.Artifact> artifacts = build.getArtifacts();
        if (artifacts.isEmpty()) {
            listener.getLogger().println("No artifacts found");
            return true;
        }


        MediaType mt = MediaType.parse("application/octet-stream");

        MultipartBuilder multipart = new MultipartBuilder();
        multipart.type(MultipartBuilder.FORM);

        multipart.addFormDataPart("appId", appId);
        multipart.addFormDataPart("email", email);
        multipart.addFormDataPart("token", token);

        for (Run<?, ?>.Artifact artifact : artifacts) {
            RequestBody rb = RequestBody.create(mt, artifact.getFile());
            multipart.addFormDataPart(artifact.getFileName(), artifact.getFileName(), rb);
        }

        Request.Builder builder = new Request.Builder();
        builder.url(url);
        builder.post(multipart.build());

        Request request = builder.build();
        OkHttpClient client = new OkHttpClient();
        Response response = client.newCall(request).execute();

        if(!response.isSuccessful()) {
            String msg = String.format("Endpoint %s replied with code %d", url, response.code());
            throw new IOException(msg);
        }

        return true;
    }

    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> { // Publisher because Notifiers are a type of publisher

        static UrlValidator urlValidator = new UrlValidator(new String[] {"https", "http"});

        public DescriptorImpl() {
            super(AppConnectNotifier.class);
        }

        @Override
        public String getDisplayName() {
            return "AppConnect Publisher";
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        public FormValidation doCheckUrl(@QueryParameter String value) {
            if (!urlValidator.isValid(value)) {
                return FormValidation.error("Invalid URL");

            }
            return FormValidation.ok();
        }

        public FormValidation doCheckEmail(@QueryParameter String value) {
            if (!EmailValidator.getInstance().isValid(value)) {
                return FormValidation.error("Invalid email");

            }
            return FormValidation.ok();
        }



    }

}

