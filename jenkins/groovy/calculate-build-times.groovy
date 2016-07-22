def buildDurationsOverLastNBuilds(job, numOfBuilds = 10) {
  def buildDurations = []
  def referenceBuild = job.getLastBuild()
  for (i=0;i<numOfBuilds;i++) {
    buildDurations << referenceBuild.duration
    def tempBuild = referenceBuild
    referenceBuild = referenceBuild.getPreviousBuild()
    if (referenceBuild == null) {
      println '>>> no further build found after #' + tempBuild.number
      break
    }
  }
  buildDurations
}

def averageBuildDurationOverNBuilds(job, numOfBuilds = 10) {
  def referenceBuild = job.getLastBuild()
  def averageBuildDuration = 0L
  for (i=0;i<numOfBuilds;i++) {
    averageBuildDuration += referenceBuild.duration
    def tempBuild = referenceBuild
    referenceBuild = referenceBuild.getPreviousBuild()
    if (referenceBuild == null) {
      println '>>> no further build found after #' + tempBuild.number
      break
    }
  }
  averageBuildDuration / numOfBuilds
}

nBuilds = 30
percentile = 95

def jobs = Jenkins.instance.getItems().findAll { it.name.contains('performance')}

jobs.each { job ->

  println job.name
  
  def firstBuild = job.getFirstBuild()
  println 'First build duration: ' + firstBuild.duration / 1000
  def lastBuild = job.getLastBuild()
  println 'Last build duration: ' + lastBuild.duration / 1000
  println 'Average build duration over last ' + nBuilds + ' runs in up rounded minutes: ' + Math.ceil(averageBuildDurationOverNBuilds(job, nBuilds) / (1000 * 60))
  def buildDurations = buildDurationsOverLastNBuilds(job, nBuilds).sort()
  println 'Build durations from last ' + nBuilds + ': ' + buildDurations
  println 'Longest build from last ' + nBuilds + ' in up rounded minutes: ' + Math.ceil(buildDurations.last() / (1000 * 60))
  println percentile + 'th percentile from last ' + nBuilds + ' in up rounded minutes: ' + Math.ceil(buildDurations[((int)(buildDurations.size() * new Double('0.'+percentile)))] / (1000 * 60))
    
  println '=========='
  
}
