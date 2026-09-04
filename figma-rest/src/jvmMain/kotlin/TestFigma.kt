import org.openrndr.figma.rest.FigmaApi

private val team = "1320724218338108242"
private val projectName = "Demo Rsync"
private val projectFile = "Design"
private val filePage = "Assets"

fun main() {

    val api = FigmaApi(okFetcher(System.getenv("figma_key")))
    println(api.me())
    val projects = (api.teamProjects(team))
    println(projects)
    println("Fetching assets for '${projects.name}' > '$projectName' > '$projectFile' > '$filePage' ")
    val targetProject = projects.projects.find { it.name == projectName }
    require(targetProject != null) { "project '$projectName' not found" }
    val projectFiles = api.projectFiles(targetProject.id)
    val targetFile = projectFiles.files.find { it.name == projectFile }
    require(targetFile != null) { "file '$projectFile' not found in project '${projectName}'" }

    val f = api.file(targetFile.key)
}