package org.openmrs.module.locationbasedaccess.extension.html;

import org.openmrs.Location;
import org.openmrs.LocationTag;
import org.openmrs.User;
import org.openmrs.api.context.Context;
import org.openmrs.module.Extension;
import org.openmrs.module.locationbasedaccess.LocationBasedAccessConstants;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Plugs a "which locations can this user access" checkbox list into legacyui's classic
 * Add/Edit User page, at the {@code org.openmrs.userForm.custom.extension} point that page
 * already exposes. The checkboxes drive a single hidden "value" input paired with a hidden
 * "property" input fixed to {@link LocationBasedAccessConstants#LOCATION_USER_PROPERTY_NAME} -
 * legacyui's UserFormController already persists any submitted property/value pairs onto the
 * user (see its handleSubmission method), so no changes to legacyui itself are needed.
 */
public class UserFormLocationExtension extends Extension {

	@Override
	public MEDIA_TYPE getMediaType() {
		return MEDIA_TYPE.html;
	}

	@Override
	public String getOverrideContent(String bodyContent) {
		String userId = getParameterMap() == null ? null : getParameterMap().get("userId");

		List<String> selectedUuids = new ArrayList<String>();
		if (userId != null && !userId.trim().isEmpty()) {
			try {
				User user = Context.getUserService().getUser(Integer.valueOf(userId.trim()));
				if (user != null) {
					String existing = user.getUserProperty(LocationBasedAccessConstants.LOCATION_USER_PROPERTY_NAME);
					if (existing != null && !existing.trim().isEmpty()) {
						selectedUuids.addAll(Arrays.asList(existing.split(",")));
					}
				}
			}
			catch (NumberFormatException e) {
				// not a valid user id (e.g. still creating a new user) - nothing pre-selected
			}
		}

		List<Location> locations = getLoginLocations();

		StringBuilder html = new StringBuilder();
		html.append("<td valign=\"top\">")
		        .append(Context.getMessageSourceService().getMessage("locationbasedaccess.location"))
		        .append("</td>");
		html.append("<td valign=\"top\">");
		html.append("<input type=\"hidden\" name=\"property\" value=\"")
		        .append(LocationBasedAccessConstants.LOCATION_USER_PROPERTY_NAME).append("\"/>");
		html.append("<input type=\"hidden\" id=\"lbacLocationValue\" name=\"value\" value=\"")
		        .append(escapeHtml(join(selectedUuids))).append("\"/>");

		for (Location location : locations) {
			boolean checked = selectedUuids.contains(location.getUuid());
			html.append("<div><input type=\"checkbox\" class=\"lbacLocationCheckbox\" id=\"lbacLoc-")
			        .append(location.getUuid()).append("\" value=\"").append(location.getUuid()).append("\"")
			        .append(checked ? " checked=\"checked\"" : "").append("/> ")
			        .append("<label for=\"lbacLoc-").append(location.getUuid()).append("\">")
			        .append(escapeHtml(location.getName())).append("</label></div>");
		}

		html.append("<script type=\"text/javascript\">").append("(function() {")
		        .append("  function lbacUpdateLocationValue() {").append("    var checked = [];")
		        .append("    var boxes = document.getElementsByClassName('lbacLocationCheckbox');")
		        .append("    for (var i = 0; i < boxes.length; i++) { if (boxes[i].checked) { checked.push(boxes[i].value); } }")
		        .append("    document.getElementById('lbacLocationValue').value = checked.join(',');").append("  }")
		        .append("  var boxes = document.getElementsByClassName('lbacLocationCheckbox');")
		        .append("  for (var i = 0; i < boxes.length; i++) { boxes[i].onchange = lbacUpdateLocationValue; }")
		        .append("})();").append("</script>");

		html.append("</td>");
		return html.toString();
	}

	private List<Location> getLoginLocations() {
		LocationTag tag = Context.getLocationService().getLocationTagByName("Login Location");
		if (tag != null) {
			List<Location> tagged = Context.getLocationService().getLocationsByTag(tag);
			if (tagged != null && !tagged.isEmpty()) {
				return tagged;
			}
		}
		return Context.getLocationService().getAllLocations();
	}

	private String join(List<String> values) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < values.size(); i++) {
			if (i > 0) {
				sb.append(",");
			}
			sb.append(values.get(i));
		}
		return sb.toString();
	}

	private String escapeHtml(String s) {
		if (s == null) {
			return "";
		}
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}
}
