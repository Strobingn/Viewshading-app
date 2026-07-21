package com.viewshed.app

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.viewshed.app.databinding.ActivityCollaborationBinding
import com.viewshed.app.viewshed.AnalysisSessionManager
import com.viewshed.app.viewshed.CollaborationClient
import com.viewshed.app.viewshed.CollaborationProject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date

class CollaborationActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCollaborationBinding
    private val gson = Gson()
    private var projects = emptyList<CollaborationProject>()
    private var selected: CollaborationProject? = null
    private var pollJob: Job? = null
    private var lastCommentTime = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePreferences.applySaved(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityCollaborationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.collaborationRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
        val preferences = getSharedPreferences("collaboration", MODE_PRIVATE)
        binding.etCollaborationUrl.setText(
            preferences.getString("url", BuildConfig.DEFAULT_BACKEND_URL).orEmpty(),
        )
        binding.etCollaborationUser.setText(preferences.getString("user", "field-team").orEmpty())
        binding.etCollaborationToken.setText(preferences.getString("token", "").orEmpty())

        binding.collaborationToolbar.setNavigationOnClickListener { finish() }
        binding.btnCollaborationConnect.setOnClickListener { refreshProjects() }
        binding.btnCollaborationCreate.setOnClickListener { promptCreateProject() }
        binding.btnCollaborationSync.setOnClickListener { syncLatestAnalysis() }
        binding.btnCollaborationComment.setOnClickListener { promptComment() }
        binding.btnCollaborationVersions.setOnClickListener { showVersions() }
        binding.btnCollaborationMember.setOnClickListener { promptMember() }
    }

    override fun onStart() {
        super.onStart()
        startPolling()
    }

    override fun onStop() {
        pollJob?.cancel()
        pollJob = null
        super.onStop()
    }

    private fun client(): CollaborationClient {
        val url = binding.etCollaborationUrl.text?.toString()?.trim().orEmpty()
        val user = binding.etCollaborationUser.text?.toString()?.trim().orEmpty()
        require(url.isNotBlank()) { "Enter the backend URL" }
        require(user.isNotBlank()) { "Enter your team ID" }
        getSharedPreferences("collaboration", MODE_PRIVATE).edit()
            .putString("url", url)
            .putString("user", user)
            .putString("token", binding.etCollaborationToken.text?.toString().orEmpty())
            .apply()
        return CollaborationClient(url, user, binding.etCollaborationToken.text?.toString().orEmpty())
    }

    private fun refreshProjects(selectId: String? = selected?.id) {
        binding.btnCollaborationConnect.isEnabled = false
        binding.tvCollaborationStatus.text = "Connecting…"
        lifecycleScope.launch {
            try {
                projects = client().listProjects()
                renderProjects()
                selected = projects.firstOrNull { it.id == selectId }
                selected?.let(::selectProject)
                if (selected == null) {
                    binding.tvCollaborationStatus.text =
                        if (projects.isEmpty()) "Connected · no projects yet" else "Connected · select a project"
                    updateActions()
                }
            } catch (error: Exception) {
                binding.tvCollaborationStatus.text = "Connection failed: ${error.message}"
            } finally {
                binding.btnCollaborationConnect.isEnabled = true
            }
        }
    }

    private fun renderProjects() {
        binding.collaborationProjectList.removeAllViews()
        projects.forEach { project ->
            binding.collaborationProjectList.addView(MaterialButton(this).apply {
                text = "${project.name} · v${project.version} · ${project.role}"
                isAllCaps = false
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                setOnClickListener { selectProject(project) }
            })
        }
    }

    private fun selectProject(project: CollaborationProject) {
        selected = project
        lastCommentTime = 0.0
        binding.tvCollaborationStatus.text =
            "Selected ${project.name} · version ${project.version} · role ${project.role}"
        updateActions()
        pollComments(showEmpty = false)
    }

    private fun updateActions() {
        val project = selected
        val canWrite = project?.role in setOf("editor", "owner")
        binding.btnCollaborationSync.isEnabled = canWrite
        binding.btnCollaborationComment.isEnabled = project != null
        binding.btnCollaborationVersions.isEnabled = project != null
        binding.btnCollaborationMember.isEnabled = project?.role == "owner"
    }

    private fun promptCreateProject() {
        val input = EditText(this).apply {
            hint = "Project name"
            setPadding(48, 24, 48, 8)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("New team project")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isBlank()) return@setPositiveButton
                lifecycleScope.launch {
                    try {
                        val project = client().createProject(name, latestSessionPayload())
                        toast("Project created")
                        refreshProjects(project.id)
                    } catch (error: Exception) {
                        toast("Create failed: ${error.message}")
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun syncLatestAnalysis() {
        val project = selected ?: return
        val payload = latestSessionPayload()
        if (payload.size() == 0) {
            toast("Run a viewshed calculation first.")
            return
        }
        binding.btnCollaborationSync.isEnabled = false
        lifecycleScope.launch {
            try {
                val version = client().addVersion(project.id, payload, "Android field sync")
                toast("Synced version $version")
                refreshProjects(project.id)
            } catch (error: Exception) {
                toast("Sync failed: ${error.message}")
                updateActions()
            }
        }
    }

    private fun latestSessionPayload(): JsonObject {
        val session = AnalysisSessionManager.load(File(filesDir, "last_session.json")) ?: return JsonObject()
        return gson.toJsonTree(session).asJsonObject
    }

    private fun promptComment() {
        val project = selected ?: return
        val input = EditText(this).apply {
            hint = "Field update or review comment"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            setPadding(48, 24, 48, 8)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Comment on ${project.name}")
            .setView(input)
            .setPositiveButton("Post") { _, _ ->
                val text = input.text?.toString()?.trim().orEmpty()
                if (text.isBlank()) return@setPositiveButton
                lifecycleScope.launch {
                    try {
                        client().addComment(project.id, text)
                        toast("Comment posted")
                        pollComments(showEmpty = false)
                    } catch (error: Exception) {
                        toast("Comment failed: ${error.message}")
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showVersions() {
        val project = selected ?: return
        lifecycleScope.launch {
            try {
                val versions = client().versions(project.id)
                val labels = versions.map { version ->
                    val date = DateFormat.getDateTimeInstance().format(Date((version.createdAt * 1000).toLong()))
                    "v${version.version} · ${version.message} · ${version.authorId} · $date"
                }.toTypedArray()
                MaterialAlertDialogBuilder(this@CollaborationActivity)
                    .setTitle("${project.name} versions")
                    .setItems(labels, null)
                    .setPositiveButton("Done", null)
                    .show()
            } catch (error: Exception) {
                toast("History failed: ${error.message}")
            }
        }
    }

    private fun promptMember() {
        val project = selected ?: return
        val input = EditText(this).apply {
            hint = "Member team ID"
            setPadding(48, 24, 48, 8)
        }
        val roles = arrayOf("viewer", "editor", "owner")
        MaterialAlertDialogBuilder(this)
            .setTitle("Set member role")
            .setView(input)
            .setSingleChoiceItems(roles, 0, null)
            .setPositiveButton("Save") { dialog, _ ->
                val member = input.text?.toString()?.trim().orEmpty()
                val which = (dialog as? androidx.appcompat.app.AlertDialog)?.listView?.checkedItemPosition ?: 0
                if (member.isBlank()) return@setPositiveButton
                lifecycleScope.launch {
                    try {
                        client().setMember(project.id, member, roles[which.coerceIn(0, roles.lastIndex)])
                        toast("Member role updated")
                    } catch (error: Exception) {
                        toast("Member update failed: ${error.message}")
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun startPolling() {
        if (pollJob != null) return
        pollJob = lifecycleScope.launch {
            while (isActive) {
                if (selected != null) pollComments(showEmpty = false)
                delay(15_000)
            }
        }
    }

    private fun pollComments(showEmpty: Boolean) {
        val project = selected ?: return
        lifecycleScope.launch {
            try {
                val comments = client().comments(project.id, lastCommentTime)
                if (comments.isNotEmpty()) {
                    lastCommentTime = comments.maxOf { it.createdAt }
                    val latest = comments.last()
                    binding.tvCollaborationStatus.text =
                        "${project.name} · ${comments.size} new comment(s) · ${latest.authorId}: ${latest.body.take(100)}"
                } else if (showEmpty) {
                    binding.tvCollaborationStatus.text = "${project.name} · no new comments"
                }
            } catch (_: Exception) {
                // Keep the workspace usable offline; the next poll retries.
            }
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
