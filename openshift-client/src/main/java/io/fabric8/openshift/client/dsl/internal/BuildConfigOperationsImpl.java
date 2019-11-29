/**
 * Copyright (C) 2015 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fabric8.openshift.client.dsl.internal;

import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.dsl.Triggerable;
import io.fabric8.kubernetes.client.dsl.Typeable;
import io.fabric8.kubernetes.client.dsl.Watchable;
import io.fabric8.kubernetes.client.dsl.base.BaseOperation;
import io.fabric8.kubernetes.client.dsl.base.OperationContext;
import io.fabric8.kubernetes.client.utils.URLUtils;
import io.fabric8.kubernetes.client.utils.Utils;
import io.fabric8.openshift.api.model.Build;
import io.fabric8.openshift.api.model.BuildConfig;
import io.fabric8.openshift.api.model.BuildConfigList;
import io.fabric8.openshift.api.model.BuildList;
import io.fabric8.openshift.api.model.BuildRequest;
import io.fabric8.openshift.api.model.DoneableBuildConfig;
import io.fabric8.openshift.api.model.WebHookTrigger;
import io.fabric8.openshift.client.OpenShiftConfig;
import io.fabric8.openshift.client.dsl.BuildConfigOperation;
import io.fabric8.openshift.client.dsl.BuildConfigResource;
import io.fabric8.openshift.client.dsl.InputStreamable;
import io.fabric8.openshift.client.dsl.TimeoutInputStreamable;
import io.fabric8.openshift.client.dsl.buildconfig.AsFileTimeoutInputStreamable;
import io.fabric8.openshift.client.dsl.buildconfig.AuthorEmailable;
import io.fabric8.openshift.client.dsl.buildconfig.AuthorMessageAsFileTimeoutInputStreamable;
import io.fabric8.openshift.client.dsl.buildconfig.CommitterAuthorMessageAsFileTimeoutInputStreamable;
import io.fabric8.openshift.client.dsl.buildconfig.CommitterEmailable;
import io.fabric8.openshift.client.dsl.buildconfig.MessageAsFileTimeoutInputStreamable;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okio.BufferedSink;
import okio.Okio;
import okio.Source;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.concurrent.TimeUnit;

import static io.fabric8.openshift.client.OpenShiftAPIGroups.BUILD;

public class BuildConfigOperationsImpl extends OpenShiftOperation<BuildConfig, BuildConfigList, DoneableBuildConfig,
  BuildConfigResource<BuildConfig, DoneableBuildConfig, Void, Build>>
        implements BuildConfigOperation {

  public static final String BUILD_CONFIG_LABEL = "openshift.io/build-config.name";
  public static final String BUILD_CONFIG_ANNOTATION = "openshift.io/build-config.name";

  private final String secret;
  private final String triggerType;

  private final String authorName;
  private final String authorEmail;
  private final String committerName;
  private final String committerEmail;
  private final String commit;
  private final String message;
  private final String asFile;

  private final long timeout;
  private final TimeUnit timeoutUnit;

  public BuildConfigOperationsImpl(OkHttpClient client, OpenShiftConfig config) {
    this(new BuildConfigOperationContext().withOkhttpClient(client).withConfig(config));
  }

  public BuildConfigOperationsImpl(BuildConfigOperationContext context) {
    super(context.withApiGroupName(BUILD)
      .withPlural("buildconfigs"));

    this.type = BuildConfig.class;
    this.listType = BuildConfigList.class;
    this.doneableType = DoneableBuildConfig.class;

    this.triggerType = context.getTriggerType();
    this.secret = context.getSecret();
    this.authorName = context.getAuthorName();
    this.authorEmail = context.getAuthorEmail() ;
    this.committerName = context.getCommitterName();
    this.committerEmail = context.getCommitterEmail();
    this.commit = context.getCommit();
    this.message = context.getMessage();
    this.asFile = context.getAsFile();
    this.timeout = context.getTimeout();
    this.timeoutUnit = context.getTimeoutUnit();
  }

  @Override
  public BuildConfigOperationsImpl newInstance(OperationContext context) {
    return new BuildConfigOperationsImpl((BuildConfigOperationContext) context);
  }

  public  BuildConfigOperationContext getContext() {
    return (BuildConfigOperationContext) context;
  }

  @Override
  public Build instantiate(BuildRequest request) {
    try {
      updateApiVersion(request);
      URL instantiationUrl = new URL(URLUtils.join(getResourceUrl().toString(), "instantiate"));
      RequestBody requestBody = RequestBody.create(JSON, BaseOperation.JSON_MAPPER.writer().writeValueAsString(request));
      Request.Builder requestBuilder = new Request.Builder().post(requestBody).url(instantiationUrl);
      return handleResponse(requestBuilder, Build.class);
    } catch (Exception e) {
      throw KubernetesClientException.launderThrowable(e);
    }
  }

  @Override
  public CommitterAuthorMessageAsFileTimeoutInputStreamable<Build> instantiateBinary() {
    return new BuildConfigOperationsImpl(getContext());
  }


  @Override
  public Void trigger(WebHookTrigger trigger) {
    try {
      //TODO: This needs some attention.
      String triggerUrl = URLUtils.join(getResourceUrl().toString(), "webhooks", secret, triggerType);
      RequestBody requestBody = RequestBody.create(JSON, BaseOperation.JSON_MAPPER.writer().writeValueAsBytes(trigger));
      Request.Builder requestBuilder = new Request.Builder()
        .post(requestBody)
        .url(triggerUrl)
        .addHeader("X-Github-Event", "push");
      handleResponse(requestBuilder, null);
    } catch (Exception e) {
      throw KubernetesClientException.launderThrowable(e);
    }
    return null;
  }

  @Override
  public Triggerable<WebHookTrigger, Void> withType(String triggerType) {
    return new BuildConfigOperationsImpl(getContext().withTriggerType(triggerType));
  }

  @Override
  public Watchable<Watch, Watcher<BuildConfig>> withResourceVersion(String resourceVersion) {
    return new BuildConfigOperationsImpl(getContext().withResourceVersion(resourceVersion));
  }

  /*
   * Labels are limited to 63 chars so need to first truncate the build config name (if required), retrieve builds with matching label,
   * then check the build config name against the builds' build config annotation which have no such length restriction (but
   * aren't usable for searching). Would be better if referenced build config was available via fields but it currently isn't...
   */
  private void deleteBuilds() {
    if (getName() == null) {
        return;
    }
    String buildConfigLabelValue = getName().substring(0, Math.min(getName().length(), 63));
    BuildList matchingBuilds = new BuildOperationsImpl(client, (OpenShiftConfig) config).inNamespace(namespace).withLabel(BUILD_CONFIG_LABEL, buildConfigLabelValue).list();

    if (matchingBuilds.getItems() != null) {

      for (Build matchingBuild : matchingBuilds.getItems()) {

        if (matchingBuild.getMetadata() != null &&
          matchingBuild.getMetadata().getAnnotations() != null &&
          getName().equals(matchingBuild.getMetadata().getAnnotations().get(BUILD_CONFIG_ANNOTATION))) {

          new BuildOperationsImpl(client, (OpenShiftConfig) config).inNamespace(matchingBuild.getMetadata().getNamespace()).withName(matchingBuild.getMetadata().getName()).delete();

        }

      }

    }
  }

  @Override
  public Build fromInputStream(final InputStream inputStream) {
    return fromInputStream(inputStream, -1L);
  }

  @Override
  public Build fromFile(final File file) {
    if (!file.exists()) {
      throw new IllegalArgumentException("Can't instantiate binary build from the specified file. The file does not exists");
    }
    try (InputStream is = new FileInputStream(file)) {
      // Use a length to prevent chunked encoding with OkHttp, which in turn
      // doesn't work with 'Expect: 100-continue' negotiation with the OpenShift API server
      return fromInputStream(is, file.length());
    } catch (Throwable t) {
      throw KubernetesClientException.launderThrowable(t);
    }
  }

  private Build fromInputStream(final InputStream inputStream, final long contentLength) {
    try {

      RequestBody requestBody = new RequestBody() {
        @Override
        public MediaType contentType() {
          return MediaType.parse("application/octet-stream");
        }

        @Override
        public long contentLength() throws IOException {
          return contentLength;
        }

        @Override
        public void writeTo(BufferedSink sink) throws IOException {
          Source source = null;
          try {
            source = Okio.source(inputStream);
            OutputStream os = sink.outputStream();

            sink.writeAll(source);
          } catch (IOException e) {
            throw KubernetesClientException.launderThrowable("Can't instantiate binary build, due to error reading/writing stream. "
                                                             + "Can be caused if the output stream was closed by the server.", e);
          }
        }
      };

      OkHttpClient newClient = client.newBuilder()
                                     .readTimeout(timeout, timeoutUnit)
                                     .writeTimeout(timeout, timeoutUnit)
                                     .build();
      Request.Builder requestBuilder =
          new Request.Builder().post(requestBody)
                               .header("Expect", "100-continue")
                               .url(getQueryParameters());
      return handleResponse(newClient, requestBuilder, Build.class);
    } catch (Exception e) {
      throw KubernetesClientException.launderThrowable(e);
    }
  }

  private String getQueryParameters() throws MalformedURLException {
    StringBuilder sb = new StringBuilder();
    sb.append(URLUtils.join(getResourceUrl().toString(), "instantiatebinary"));
    if (Utils.isNullOrEmpty(message)) {
      sb.append("?commit=");
    } else {
      sb.append("?commit=").append(message);
    }

    if (!Utils.isNullOrEmpty(authorName)) {
      sb.append("&revision.authorName=").append(authorName);
    }

    if (!Utils.isNullOrEmpty(authorEmail)) {
      sb.append("&revision.authorEmail=").append(authorEmail);
    }

    if (!Utils.isNullOrEmpty(committerName)) {
      sb.append("&revision.committerName=").append(committerName);
    }

    if (!Utils.isNullOrEmpty(committerEmail)) {
      sb.append("&revision.committerEmail=").append(committerEmail);
    }

    if (!Utils.isNullOrEmpty(commit)) {
      sb.append("&revision.commit=").append(commit);
    }

    if (!Utils.isNullOrEmpty(asFile)) {
      sb.append("&asFile=").append(asFile);
    }
    return sb.toString();
  }

  @Override
  public TimeoutInputStreamable<Build> asFile(String fileName) {
    return new BuildConfigOperationsImpl(getContext().withAsFile(fileName));
  }

  @Override
  public MessageAsFileTimeoutInputStreamable<Build> withAuthorEmail(String email) {
    return new BuildConfigOperationsImpl(getContext().withAuthorEmail(email));
  }

  @Override
  public AuthorMessageAsFileTimeoutInputStreamable<Build> withCommitterEmail(String committerEmail) {
    return new BuildConfigOperationsImpl(getContext().withCommitterEmail(committerEmail));
  }

  @Override
  public AsFileTimeoutInputStreamable<Build> withMessage(String message) {
    return new BuildConfigOperationsImpl(getContext().withMessage(message));
  }

  @Override
  public AuthorEmailable<MessageAsFileTimeoutInputStreamable<Build>> withAuthorName(String authorName) {
    return new BuildConfigOperationsImpl(getContext().withAuthorName(authorName));
  }

  @Override
  public CommitterEmailable<AuthorMessageAsFileTimeoutInputStreamable<Build>> withCommitterName(String committerName) {
    return new BuildConfigOperationsImpl(getContext().withCommitterName(committerName));
  }

  @Override
  public InputStreamable<Build> withTimeout(long timeout, TimeUnit unit) {
    return new BuildConfigOperationsImpl(getContext().withTimeout(timeout).withTimeoutUnit(unit));
  }

  @Override
  public InputStreamable<Build> withTimeoutInMillis(long timeoutInMillis) {
    return withTimeout(timeoutInMillis, TimeUnit.MILLISECONDS);
  }

  @Override
  public Typeable<Triggerable<WebHookTrigger, Void>> withSecret(String secret) {
    return new BuildConfigOperationsImpl(getContext().withSecret(secret));
  }

}
