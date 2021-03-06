<%@ page language="java" contentType="text/html; charset=ISO-8859-1"
    pageEncoding="ISO-8859-1" import="org.mitre.openid.connect.repository.db.util.*,java.util.*" %>
<%@ taglib prefix="o" tagdir="/WEB-INF/tags" %> 

<o:header title="User Management - User List Page" />
<o:topbar />
<o:includes />
<%
	String base = (String) request.getAttribute("base"); // Calculated in o:include
	String p = request.getParameter("page");
	if (p != null && p.trim().length() > 0) {
		request.setAttribute("page", new Integer(p));
	} else {
		request.setAttribute("page", 0);
	}
	String s = request.getParameter("sort_on");
	if (s != null && s.trim().length() > 0) {
		request.setAttribute("sort_on", s);
	} else {
		request.setAttribute("sort_on", "FIRST_NAME");
	}
	List<Breadcrumb> bc = new java.util.ArrayList<Breadcrumb>();
	bc.add(new Breadcrumb("Home", base + "/"));
	bc.add(new Breadcrumb("Manage Users"));
	request.setAttribute("bc", bc);
%>   
<o:header />
<o:breadcrumbs-db breadcrumbs="${bc}" />
<h2 class="span12">Manage Users</h2>
<a class="span12" href="${base}/users/addUser">Add User</a>
<div id="users">
<table id="people" class="table-striped span12">
</table>
<div id="paginator" class="span12"></div>
</div>
<input id="page" type="hidden" name="page" value="${page}">
<input id="sort_on" type="hidden" name="sort_on" value="${sort_on}">
<br clear="all">
<o:copyright />
<o:footer-db include="manage_users" />