/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.contrib.mailarchive.internal.timeline;

import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map.Entry;
import java.util.TreeMap;

import org.apache.commons.lang.StringEscapeUtils;
import org.slf4j.Logger;
import org.xwiki.contrib.mailarchive.MailArchiveConfiguration;
import org.xwiki.contrib.mailarchive.internal.DefaultMailArchive;
import org.xwiki.query.Query;
import org.xwiki.query.QueryException;
import org.xwiki.query.QueryManager;

import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;

/**
 * @version $Id$
 */
public class TimeLine
{
    private MailArchiveConfiguration config;

    private XWiki xwiki;

    private XWikiContext context;

    private Logger logger;

    private QueryManager queryManager;

    public TimeLine(MailArchiveConfiguration config, XWiki xwiki, XWikiContext context, QueryManager queryManager,
        Logger logger)
    {
        this.config = config;
        this.xwiki = xwiki;
        this.context = context;
        this.logger = logger;
        this.queryManager = queryManager;
    }

    public void compute() throws XWikiException
    {

        int count = 200;
        XWikiDocument curdoc = xwiki.getDocument("", context);
        TreeMap<Long, String> sortedEvents = new TreeMap();
        int maxstrlenght = 150;
        String skinurl = "http://r-wikiggs.gemalto.com/xwiki/bin/download/XWiki/GSESkin"; // TODO hard-coded gse skin
                                                                                          // url ...

        // Utility class for mail archive
        // def tools = xwiki.parseGroovyFromPage('MailArchiveCode.ToolsGroovyClass');

        // Set loading user in context (for rights)
        String loadingUser = config.getLoadingUser();
        context.setUser(loadingUser);

        try {
            // Add Topics durations
            String xwql =
                "select topic.name from Document doc, doc.object(" + DefaultMailArchive.SPACE_CODE
                    + ".MailTopicClass) as topic order by topic.lastupdatedate desc";
            List<String> result = queryManager.createQuery(xwql, Query.XWQL).setLimit(count).execute();

            for (String item : result) {
                XWikiDocument doc = xwiki.getDocument(item, context);
                BaseObject obj = doc.getObject("MailArchiveCode.MailTopicClass");
                if (obj != null) {
                    try {
                        String author = obj.getStringValue("author");
                        String subject = obj.getStringValue("subject");
                        String docurl = doc.getFullName();
                        String docshortname = subject;
                        String topicId = obj.getStringValue("topicid");
                        String icon = "";
                        String action = "";
                        String link = "";

                        Date date = obj.getDateValue("startdate");
                        Date end = obj.getDateValue("lastupdatedate");
                        if (date != null && date.equals(end)) {
                            // Add 10 min just to see the tape
                            end.setTime(end.getTime() + 600000);
                        }
                        String extract = getTopicMails(topicId, subject);
                        String type = obj.getStringValue("type");
                        String tags = "";
                        // TODO not generic
                        if (doc.getTags(context).contains("GGS_WW_")) {
                            tags = "GGS_WW";
                        }
                        for (String tag : doc.getTagsList(context))/* TODO .grep("^Community.*$").each() */{
                            if (tags == "GGS_WW") {
                                tags = "";
                            }
                            tags += tag + ' ';
                        }

                        if (type == "Mail") {
                            if (!xwiki.getDocument(doc.getCreator(), context).isNew()) {
                                icon =
                                    xwiki.getDocument(doc.getCreator(), context).getURL("download", context)
                                        + "/ldapavatar.jpg";
                            } else {
                                icon =
                                    xwiki.getDocument("XWiki.XWikiUserSheet", context).getURL("download", context)
                                        + "/noavatar.png";

                            }
                            action = "Topic created";
                            sortedEvents.put(
                                date.getTime(),
                                new StringBuilder("<event start=\"")
                                    .append(date.toLocaleString())
                                    .append("\" end=\"")
                                    .append(end.toLocaleString())
                                    .append("\" title=\"")
                                    .append(StringEscapeUtils.escapeXml(docshortname))
                                    .append("\" icon=\"")
                                    .append(icon)
                                    .append("\" image=\"")
                                    .append(icon)
                                    .append("\" classname=\"")
                                    .append(tags)
                                    .append("\" durationEvent=\"true\" link=\"")
                                    .append(doc.getURL("view", context))
                                    .append("\">")
                                    .append(
                                        StringEscapeUtils.escapeXml((!tags.equals("") ? "<span class=\"tape-" + tags
                                            + "\">___</span> " + tags + "<br/>" : "")
                                            + "<br/> " + action + " by " + author + "<br/> " + extract))
                                    .append("</event>").toString());

                        } else {
                            if (type == "Newsletter") {
                                action = "Newsletter posted";
                                icon =
                                    "${xwiki.getDocument('XWiki.GSESkin').getURL('download')}/667%2D2_pupils_speech.png";
                            }
                            if (type == "Product Release") {
                                action = "Product Release published";
                                icon = "http://r-wikiggs.gemalto.com/xwiki/resources/icons/silk/cd.gif";
                            }
                            link =
                                xwiki.getDocument("MailArchive.M" + doc.getName().substring(1, doc.getName().length()),
                                    context).getURL("view", context);
                            sortedEvents.put(
                                date.getTime(),
                                "<event start=\""
                                    + date.toLocaleString()
                                    + "\" title=\""
                                    + StringEscapeUtils.escapeXml(docshortname)
                                    + "\" icon=\""
                                    + icon
                                    + "\" image=\""
                                    + icon
                                    + "\" link=\""
                                    + link
                                    + "\" >"
                                    + StringEscapeUtils.escapeXml((!tags.equals("") ? "<span class=\"tape-" + tags
                                        + "\">___</span> " + tags + "<br/>" : "")
                                        + "<br/>" + action + " by " + author + "<br/> " + extract) + "</event>");
                        }
                    } catch (Throwable t) {
                        logger.warn("Exception for " + doc, t);
                    }
                }
            }

        } catch (Throwable e) {
            logger.warn("could not compute timeline data", e);
        }

        try {
            StringBuilder content = new StringBuilder();
            content.append("<data>");
            for (Entry<Long, String> event : sortedEvents.entrySet()) {
                content.append(event.getValue() + '\n');
            }
            content.append("</data>");

            curdoc.addAttachment("TimeLineFeed-MailArchiver.xml", content.toString().getBytes(), context);
            curdoc.saveAllAttachments(context);

            // TODO path to timeline file

            FileWriter fw = new FileWriter("TimeLineFeed-MailArchiver.xml", false);
            fw.write(content.toString());
            fw.close();

            logger.debug("Loaded " + sortedEvents.size() + " into Timeline feed");

        } catch (Exception e) {
            logger.warn("Could not save timeline date", e);
        }

    }

    /**
     * @param topicid
     * @param topicsubject
     * @return
     * @throws QueryException
     * @throws XWikiException
     */
    protected String getTopicMails(String topicid, String topicsubject) throws QueryException, XWikiException
    {
        String returnVal = "";
        boolean first = true;
        String xwql_topic =
            "select doc.fullName,doc.author,mail.date,mail.messagesubject,mail.from from Document doc, doc.object(MailArchiveCode.MailClass) as  mail where  mail.topicid='${topicid}' and doc.fullName<>'MailArchiveCode.MailClassTemplate' order by mail.date asc";
        List<Object[]> msgs = queryManager.createQuery(xwql_topic, Query.XWQL).execute();
        for (Object[] msg : msgs) {
            String docfullname = (String) msg[0];
            String docauthor = (String) msg[1];
            Date maildate = (Date) msg[2];
            String mailmessagesubject = (String) msg[3];
            String mailfrom = (String) msg[4];
            // formatter for formatting dates for display
            SimpleDateFormat formatter = new SimpleDateFormat("yyyy/MM/dd HH:mm");
            XWikiDocument userdoc = xwiki.getDocument(docauthor, context);
            String user = "";
            String link = null;
            if (!userdoc.isNew()) {
                BaseObject userobj = userdoc.getObject("XWiki.XWikiUsers");
                if (userobj != null) {
                    user = userobj.getStringValue("first_name") + " " + userobj.getStringValue("last_name");
                    link = userdoc.getURL("view", context);
                }
            } else {
                // TODO replace with parse user method
                int start = mailfrom.indexOf("<");
                if (start != -1) {
                    user = mailfrom.substring(0, start);
                }
            }
            String subject = topicsubject;
            if (!first) {
                subject = mailmessagesubject.replace(topicsubject, "...");
            } else if (topicsubject.length() > 20) {
                subject = topicsubject.substring(0, 19) + "...";
            }
            returnVal +=
                "<a href=\"javascript:centerTimeline(" + maildate.getTime() + ");\">" + formatter.format(maildate)
                    + "</a> - <a href=\"" + xwiki.getDocument(docfullname, context).getURL("view", context) + "\">"
                    + subject + "</a> - " + (link != null ? "<a href=\"" + link + "\">" : "") + user
                    + (link != null ? "</a> " : "") + "'<br/>";
            first = false;
        }

        return returnVal;

    }
}