package com.improve_future.backlog_board.domain.backlog.repository

import com.improve_future.backlog_board.domain.backlog.factory.IssueFactory
import com.improve_future.backlog_board.domain.backlog.factory.ProjectFactory
import com.improve_future.backlog_board.domain.backlog.model.Issue
import com.improve_future.backlog_board.domain.backlog.model.Project
import com.improve_future.backlog_board.gateway.WebGateway
import com.nulabinc.backlog4j.IssueType
import com.nulabinc.backlog4j.CustomField as BacklogCustomField
import com.nulabinc.backlog4j.Issue as BacklogIssue
import com.nulabinc.backlog4j.Project as BacklogProject
import com.nulabinc.backlog4j.PullRequest as BacklogPullRequest
import com.nulabinc.backlog4j.api.option.GetIssuesParams
import com.nulabinc.backlog4j.api.option.PullRequestQueryParams
import com.nulabinc.backlog4j.api.option.UpdateIssueParams
import org.springframework.stereotype.Component
import java.net.URL

@Component
class BacklogRepository: AbstractBacklogRepository() {
    fun findAllProjects(spaceKey: String, apiKey: String): List<Project> {
        val backlogGateway = buildBacklogClient(spaceKey, apiKey)
        return backlogGateway.projects.map {
            ProjectFactory.createFromBacklogProject(
                    spaceKey, it) }
    }

    fun findProject(key: String): Project {
        return ProjectFactory.createFromBacklogProject(
                backlogConfig.spaceId,
                backlogGateway.getProject(key))
    }

    /**
     * @param projectKey
     * Return all un-closed issues
     */
    fun findAllUnclosedIssues(projectKey: String, milestoneId: Long?, categoryId: Long?): List<Issue> {
        val project = findProject(projectKey)
        val issueParam = GetIssueParam(listOf(project.id!!))
        if (milestoneId != null) issueParam.milestoneIds(listOf(milestoneId))
        if (categoryId != null) issueParam.categoryIds(listOf(categoryId))
        issueParam.statuses(
                listOf(
                        BacklogIssue.StatusType.Open,
                        BacklogIssue.StatusType.InProgress,
                        BacklogIssue.StatusType.Resolved))
        issueParam.sort(GetIssuesParams.SortKey.DueDate)
        issueParam.order(GetIssuesParams.Order.Asc)
        issueParam.count(100)

        return findAllIssueMap(issueParam).values.toList()
    }

    fun findAllIssues(projectKey: String, milestoneId: Long, categoryId: Long?): List<Issue> {
        val project = findProject(projectKey)

        var issueParam = GetIssueParam(listOf(project.id!!))
        issueParam.milestoneIds(listOf(milestoneId))
        if (categoryId != null) issueParam.categoryIds(listOf(categoryId))
        issueParam.parentChildType(GetIssuesParams.ParentChildType.Child)
        issueParam.resolutions(listOf(
                BacklogIssue.ResolutionType.NotSet,
                BacklogIssue.ResolutionType.CannotReproduce,
                BacklogIssue.ResolutionType.Fixed))
        issueParam.sort(GetIssuesParams.SortKey.DueDate)
        issueParam.order(GetIssuesParams.Order.Asc)
        issueParam.count(100)

        var issueList = findAllIssueList(issueParam)

        issueParam = GetIssueParam(listOf(project.id!!))
        issueParam.milestoneIds(listOf(milestoneId))
        if (categoryId != null) issueParam.categoryIds(listOf(categoryId))
        issueParam.parentChildType(GetIssuesParams.ParentChildType.NotChildNotParent)
        issueParam.resolutions(listOf(
                BacklogIssue.ResolutionType.NotSet,
                BacklogIssue.ResolutionType.CannotReproduce,
                BacklogIssue.ResolutionType.Fixed))
        issueParam.sort(GetIssuesParams.SortKey.DueDate)
        issueParam.order(GetIssuesParams.Order.Asc)
        issueParam.count(100)

        issueList += findAllIssueList(issueParam)

        return issueList
    }

    fun findAllIssuesInStartOrder(projectKey: String): List<Issue> {
        val project = findProject(projectKey)
        val issueParam = GetIssueParam(listOf(project.id!!))
        issueParam.statuses(
                listOf(
                        BacklogIssue.StatusType.Open,
                        BacklogIssue.StatusType.InProgress,
                        BacklogIssue.StatusType.Resolved))
        issueParam.sort(GetIssuesParams.SortKey.StartDate)
        issueParam.order(GetIssuesParams.Order.Asc)
        issueParam.sort(GetIssuesParams.SortKey.DueDate)
        issueParam.order(GetIssuesParams.Order.Asc)
        issueParam.count(100)

        return findAllIssueMap(issueParam).values.toList()
    }

    fun updateStatus(id: Long, statusId: Long) {
        var params = UpdateIssueParams(id.toString())
        params.status(com.nulabinc.backlog4j.Issue.StatusType.valueOf(statusId.toInt()))
        backlogGateway.updateIssue(params)
    }

    fun retrieveUserIcon(
            spaceKey: String, apiKey: String, userId: Long): ByteArray {
        return WebGateway.getImage(
                buildUserIconUrl(spaceKey, apiKey, userId),
                mapOf(
                        "apiKey" to backlogConfig.apiKey
                ))
    }

    fun retrieveProjectIcon(
            spaceKey: String, apiKey: String, projectKey: String): ByteArray {
        return WebGateway.getImage(
                buildProjectIconUrl(spaceKey, apiKey, projectKey),
                mapOf("apiKey" to apiKey)
        )
    }

    private fun buildUserIconUrl(
            spaceKey: String, apiKey: String, userId: Long): URL {
        return URL("https://$spaceKey.backlog.jp/api/v2/users/$userId/icon?apiKey=$apiKey")
    }

    private fun buildProjectIconUrl(
            spaceKey: String, apiKey: String, projectKey: String): URL {
        return URL( "https://$spaceKey.backlog.jp/api/v2/projects/$projectKey/image?apiKey=$apiKey")
    }

    private fun findAllIssueMap(condition: GetIssueParam): Map<Long, Issue> {
        val issueList = findAllIssueList(condition)
        val parentIssuesMap = mutableMapOf<Long, Issue>()
        issueList.forEach {
            if (!it.isChild())
                parentIssuesMap.put(it.id!!, it)
        }
        issueList.forEach {
            if (it.isChild())
                parentIssuesMap[it.parentIssueId!!]?.addChild(it) ?:
                        parentIssuesMap.put(it.id!!, it)
        }
        return parentIssuesMap
    }

    private fun findAllIssueList(condition: GetIssueParam): List<Issue> {
        val issueList = mutableListOf<Issue>()
        do {
            val param = condition.clone()
            param.offset(issueList.count().toLong())
            val issueChunks = this.findIssues(param)
            issueList += issueChunks
        } while (issueChunks.count() > 0)
        return issueList
    }

    private fun buildClosedIssueCondition(projectId: Long):
            GetIssuesParams {
        val issueParam: GetIssuesParams =
                GetIssuesParams(mutableListOf(projectId))
        issueParam.statuses(listOf(BacklogIssue.StatusType.Closed))
        issueParam.resolutions(listOf(
                BacklogIssue.ResolutionType.CannotReproduce,
                BacklogIssue.ResolutionType.Fixed,
                BacklogIssue.ResolutionType.NotSet,
                BacklogIssue.ResolutionType.WontFix))
        return issueParam
    }

    private fun buildFinishedPrCondition(): PullRequestQueryParams {
        var prParam = PullRequestQueryParams()
        prParam.statusType(listOf(
                BacklogPullRequest.StatusType.Closed,
                BacklogPullRequest.StatusType.Merged))
        return prParam
    }

    /**
     * Find and return Issue List
     */
    private fun findIssues(condition: GetIssueParam): List<Issue> {
        return backlogGateway.getIssues(condition).
                map { IssueFactory.createFromBacklogIssue(backlogConfig.spaceId, it) }
    }

    private fun createIssueFromBacklogIssue(backlogIssue: BacklogIssue): Issue {
        return IssueFactory.createFromBacklogIssue(
                backlogConfig.spaceId, backlogIssue)
    }
}