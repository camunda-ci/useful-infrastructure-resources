package nexus
import groovyx.net.http.AsyncHTTPBuilder
import groovyx.net.http.ContentType
import groovyx.net.http.Method
import org.apache.http.auth.UsernamePasswordCredentials
import org.apache.http.impl.auth.BasicScheme

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NexusCli {

  static final String WRITE_POLICY = "READ_ONLY, ALLOW_WRITE_ONCE, ALLOW_WRITE"
  static final String REPO_POLICY = "RELEASE, SNAPSHOT"

  static final STATUS = "service/local/status"
  static final GAV_SEARCH = "service/local/lucene/search"
  static final REPOSITORIES = "service/local/repositories"
  static final REPOSITORY = "service/local/repositories/@repoid@"
  static final GROUPS = "service/local/repo_groups"
  static final USERS = "service/local/users"
  static final REPO_STATUSES = "service/local/repository_statuses"
  static final REPO_STATUS = "service/local/repositories/@repoid@/content/?isLocal"
  static final REPO_TYPES = "service/local/components/repo_types?repoType=hosted"

  static final EXPIRE_CACHE = "service/local/data_cache/repositories/@repoid@/content" // DELETE -> 204
  static final EXPIRE_META_DATA = "service/local/metadata/repositories/@repoid@/content" // DELETE -> 204
  static final REPAIR_INDEX = "service/local/data_index/repositories/@repoid@/content" // DELETE -> 204
  static final UPDATE_INDEX = "service/local/data_incremental_index/repositories/@repoid@/content" // DELETE -> 204
  static final EMPTY_TRASH = "service/local/wastebasket" // DELETE -> 204

  static final CREATE_REPOSITORY = REPOSITORIES // POST -> 201
  // payload: {"data":{"repoType":"hosted","id":"test","name":"test","writePolicy":"ALLOW_WRITE_ONCE","browseable":true,"indexable":true,"exposed":true,"notFoundCacheTTL":1440,"repoPolicy":"RELEASE","provider":"maven2","providerRole":"org.sonatype.nexus.proxy.repository.Repository","downloadRemoteIndexes":false,"checksumPolicy":"IGNORE"}}
  static final MODIFY_REPOSITORY = REPOSITORIES + "/@repoid@" // PUT -> 200
  // payload: {"data":{"repoType":"hosted","id":"test","name":"test","writePolicy":"ALLOW_WRITE","browseable":true,"indexable":true,"exposed":true,"notFoundCacheTTL":1440,"repoPolicy":"RELEASE","provider":"maven2","providerRole":"org.sonatype.nexus.proxy.repository.Repository","defaultLocalStorageUrl":"file:/home/java/jetty-nexus/sonatype-work/nexus/storage/test","downloadRemoteIndexes":false,"checksumPolicy":"IGNORE"}}
  static final DELETE_REPOSITORY = REPOSITORIES + "/@repoid@"

  static final AVAILABLE_SCHEDULE_TYPES = "service/local/schedule_types" // GET -> 200
  static final EXISTING_SCHEDULES = "service/local/schedules" // GET -> 200, DELETE -> 204

  def settings = [
      baseUrl: 'https://localhost:8443', // for usage with wiremock
      pathPrefix: '/nexus',
      repositoryId: 'camunda-bpm-snapshots', // camunda-bpm-ee-snapshots
      groupId: 'org.camunda.*',
      artifactId: null,
      version: null,
      filterVersions: ['7.5.0-SNAPSHOT', '7.4.1-SNAPSHOT', '7.3.4-SNAPSHOT', '7.2.8-SNAPSHOT', '7.1.11-SNAPSHOT'],
      username: null,
      password: null,
      delete: false
  ]

  AsyncHTTPBuilder httpClient = null

  public NexusCli() {
    init()
  }

  public NexusCli(settings) {
    assert settings
    // merge settings with default settings
    this.settings << settings
    init()
  }

  def static void main(def args) {
    // TODO: parse args and fill settings

    def settings = [
      baseUrl: 'https://app.camunda.com',
      version: '7.5.*-SNAPSHOT',
      repositoryId: 'camunda-bpm-snapshots',
      filterVersions: ['7.5.0-SNAPSHOT', '7.4.1-SNAPSHOT', '7.3.4-SNAPSHOT', '7.2.8-SNAPSHOT', '7.1.11-SNAPSHOT'],
      username: 'user',
      password: 'password',
    ]

    NexusCli nexusCli = new NexusCli(settings)
    def result = nexusCli.searchArtifacts()
    // wait for response
    def json = result.get()

    nexusCli.printBasicResponse(json)
    nexusCli.collectVersions(json.data)
    if (nexusCli.deleteArtifacts(json.data)) {
      nexusCli.updateRepositoryMetadata()
      nexusCli.emptyTrash()
    }
    nexusCli.dispose()
  }

  def printBasicResponse(json) {
    println "Basic stats - totalcount: ${json?.totalCount}, tooManyResults: ${json?.tooManyResults}, dataSize: ${json?.data?.size()}"
  }

  def collectVersions(artifacts) {
    Set versions = new HashSet()

    artifacts.each { artifact ->
      versions << artifact.version
    }

    println 'Found versions:'
    versions.each { version ->
      println version
    }

    versions
  }

  def collectHostedRepoDetails(repoDetails, String match = "hosted") {
    repoDetails.findAll {
      it.repositoryKind == match
    }
  }

  def constructGAVSearchQuery() {
    def query = [
      'collapseresults': 'true',
      'exact': 'false'
    ]

    if (settings.groupId) {
      query << [g: settings.groupId]
    }
    if (settings.artifactId) {
      query << [a: settings.artifactId]
    }
    if (settings.version) {
      query << [v: settings.version]
    }
    if (settings.repositoryId) {
      query << [repositoryId: settings.repositoryId]
    }

    def gavSearch = { req, uri ->
      uri.path = "${settings.pathPrefix}/${GAV_SEARCH}"
      uri.query = query
    }

    gavSearch
  }

  def filterArtifactForDeletion(artifact, artifactHit) {
    def artifactCanBeDeleted = artifactHit.repositoryId == settings.repositoryId &&
        (settings.version ? artifact.version.matches(settings.version) : true) &&
        !settings.filterVersions.contains(artifact.version)

    if (artifactCanBeDeleted) {
      println "${artifact.groupId}:${artifact.artifactId}:${artifact.version} can be deleted."
    }

    artifactCanBeDeleted
  }

  def constructDeleteArtifactRequests(artifact) {
    def deleteRequests = []

    artifact.artifactHits.each { artifactHit ->

      if (filterArtifactForDeletion(artifact, artifactHit)) {
        deleteRequests << [
          path: "${settings.pathPrefix}/service/local/repositories/${artifactHit.repositoryId}/content/${artifact.groupId.replaceAll('\\.', '/')}/${artifact.artifactId}/${artifact.version}"
        ]
      }

    }

    deleteRequests
  }

  def emptyTrash() {
    executeRequest(Method.DELETE, { req, uri ->
      uri.path = "${settings.pathPrefix}/" + EMPTY_TRASH
    }).get()
  }

  def updateRepositoryMetadata() {
    executeRequest(Method.DELETE, { req, uri ->
      uri.path = "${settings.pathPrefix}/" + EXPIRE_CACHE.replace('@repoid@', settings.repositoryId)
    }).get()
    executeRequest(Method.DELETE, { req, uri ->
      uri.path = "${settings.pathPrefix}/" + EXPIRE_META_DATA.replace('@repoid@', settings.repositoryId)
    }).get();
    executeRequest(Method.DELETE, { req, uri ->
      uri.path = "${settings.pathPrefix}/" + REPAIR_INDEX.replace('@repoid@', settings.repositoryId)
    }).get();
    executeRequest(Method.DELETE, { req, uri ->
      uri.path = "${settings.pathPrefix}/" + UPDATE_INDEX.replace('@repoid@', settings.repositoryId)
    }).get();
  }

  def searchArtifacts(groupId = null, artifactId = null, version = null, repositoryId = null) {
    executeRequest(Method.GET, constructGAVSearchQuery())
  }

  def deleteArtifacts(artifacts) {
    def deleteRequests = []

    artifacts.each { artifact ->
      deleteRequests.addAll(constructDeleteArtifactRequests(artifact))
    }

    deleteRequests.each {
      println "DELETE ${it.path}"
    }

    if (settings.delete) {
      println "Trying to delete ${deleteRequests.size()} artifacts..."

      def responses = []
      CountDownLatch doneSignal = new CountDownLatch(deleteRequests.size())
      deleteRequests.each { deleteRequest ->
        responses << executeRequest(Method.DELETE,
            { req, uri ->
              uri.path = deleteRequest.path
            },
            { response ->
              response.success = { resp ->
                assert resp.status == 204
                println "Deleted ${deleteRequest.path}"
                doneSignal.countDown()
              }
              response.failure = {
                println "Failed to delete ${deleteRequest.path}"
                doneSignal.countDown()
              }
            }
        )
      }
      doneSignal.await(300, TimeUnit.SECONDS)
    } else {
      println "Nothing will be deleted because 'settings.delete' is set to 'false'"
    }

    deleteRequests && settings.delete
  }

  def init() {
    def http = new AsyncHTTPBuilder(
        poolSize : 20,
        uri : settings.baseUrl,
        contentType : ContentType.JSON
    )

    if (settings.username) {
      UsernamePasswordCredentials creds = new UsernamePasswordCredentials(settings.username, settings.password);
      def authHeader = BasicScheme.authenticate(creds, 'US-ASCII', false)
      http.headers.put(authHeader.name, authHeader.value)
    }
    http.headers << ['Accept': ContentType.JSON]
    http.ignoreSSLIssues()

    http.handler.'401' = { resp ->
      println "Access denied"
    }

    // Used for all other failure codes not handled by a code-specific handler:
    http.handler.failure = { resp ->
      println "Unexpected failure: ${resp.statusLine}"
    }

    httpClient = http
  }

  def dispose() {
    httpClient?.shutdown()
  }

  def defaultResponseHandler = { resp ->
    resp.success = { response, json ->
      assert response.status >= 200 && response.status <= 400
      json?.data?.each { artifact ->
        println artifact?.groupId + ":" + artifact?.artifactId + ":" + artifact?.version
      }
      json
    }

    resp.failure = { response, json ->
      println 'a failure occured!'
    }
  }

  def executeRequest(groovyx.net.http.Method method, requestCls, responseCls = defaultResponseHandler) {
    httpClient.request(method) { req ->
      requestCls(req, uri)
      responseCls(response)
    }
  }

//  void parseArgumentsIntoOptions() {
//    def cli = new CliBuilder(usage: 'nexus-cleanup <baseurl> <groupId:[artifactId]:[version] [repository]',
//      header: 'Options:')
//
//    cli.with {
//      h longOpt: 'help', 'Show usage informations'
//      _ longOpt: 'url', args: 1, argName: 'baseurl', 'Set URL to work with'
//      _ longOpt: 'groupId:artifactId:version'
//      _ longOpt: 'repository'
////      r longOpt: 'repository', args: 1, argName: 'repository', 'Set Repository'
//      u longOpt: 'username', args: 1, argName: 'username', 'Set username for basic authentication'
//      p longOpt: 'password', args: 1, argName: 'password', 'Set password for basic authentication'
//      v 'Enable verbose logging'
//    }
//
//    options = cli.parse(args)
//    if (!options) {
//      return
//    }
//
//    if (options.h) {
//      cli.usage()
//      return
//    }
//    mapOptionsToSettings(options, settings)
//  }

//  void mapOptionsToSettings(OptionAccessor options, settings) {
//    settings = options.getProperty('blub')
//  }

//  class NexusSearchResponse {
//    def int totalCount
//    def int from
//    def int count
//    def boolean tooManyResults
//    def boolean collapsed
//    def RepositoryDetails[] repoDetails
//    def Artifact[] data
//  }
//
//  class RepositoryDetails {
//    def String repositoryId
//    def String repositoryName
//    def String repositoryContentClass
//    def String repositoryKind
//    def String repositoryURL
//  }
//
//  class Artifact {
//    def String groupId
//    def String artifactId
//    def String version
//    def String latestSnapshot
//    def String latestSnapshotRepositoryId
//    def ArtifactHit[] artifactHits
//  }
//
//  class ArtifactHit {
//    def String repositoryId
//    def ArtifactLink[] artifactLinks
//  }
//
//  class ArtifactLink {
//    def String extension
//    def String classifier
//  }

}

