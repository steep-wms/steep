export function deleteRunInformation(pc) {
  delete pc.startTime
  delete pc.endTime
  delete pc.status
  delete pc.errorMessage
  delete pc.autoResumeAfter
  delete pc.runNumber
}
