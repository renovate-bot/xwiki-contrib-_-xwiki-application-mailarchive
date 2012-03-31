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
package org.xwiki.component.mailarchive.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.mail.AuthenticationFailedException;
import javax.mail.Flags;
import javax.mail.Folder;
import javax.mail.FolderNotFoundException;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.search.FlagTerm;

import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;
import org.xwiki.bridge.DocumentAccessBridge;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.mailarchive.MailArchive;
import org.xwiki.component.mailarchive.MailType;
import org.xwiki.component.mailarchive.internal.data.ConnectionErrors;
import org.xwiki.component.mailarchive.internal.data.MailShortItem;
import org.xwiki.component.mailarchive.internal.data.MailTypeImpl;
import org.xwiki.component.mailarchive.internal.data.TopicShortItem;
import org.xwiki.component.mailarchive.internal.exceptions.MailArchiveException;
import org.xwiki.component.phase.Initializable;
import org.xwiki.component.phase.InitializationException;
import org.xwiki.query.Query;
import org.xwiki.query.QueryException;
import org.xwiki.query.QueryManager;

/**
 * Implementation of a <tt>MailArchive</tt> component.
 */
@Component
@Singleton
public class DefaultMailArchive implements MailArchive, Initializable
{

    public static final String SPACE_HOME = "MailArchive";

    public static final String SPACE_CODE = "MailArchiveCode";

    public static final String SPACE_PREFS = "MailArchivePrefs";

    public static final String SPACE_ITEMS = "MailArchiveItems";

    /** Provides access to documents. Injected by the Component Manager. */
    @Inject
    private DocumentAccessBridge dab;

    /**
     * Secure query manager that performs checks on rights depending on the query being executed.
     */
    // TODO : @Requirement("secure") ??
    @Inject
    private QueryManager queryManager;

    @Inject
    private Logger logger;

    protected Marker marker;

    private HashMap<String, String[]> existingTopics;

    private boolean isInitialized = false;

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.component.phase.Initializable#initialize()
     */
    @Override
    public void initialize() throws InitializationException
    {
        this.marker = MarkerFactory.getMarker("MailArchiveComponent");
        try {
            loadMailTypes();
            loadMailingLists();
            this.isInitialized = true;
        } catch (MailArchiveException e) {
            throw new InitializationException("Could not initiliaze mailarchive component", e);
        }

    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.component.mailarchive.MailArchive#checkMails()
     */
    @Override
    public int checkMails(String serverPrefsDoc)
    {
        int nbMessages = -1;

        String server = (String) dab.getProperty(serverPrefsDoc, "MailArchiveCode.ServerSettingsClass", 0, "hostname");
        int port =
            Integer
                .parseInt((String) dab.getProperty(serverPrefsDoc, "MailArchiveCode.ServerSettingsClass", 0, "port"));
        String protocol =
            (String) dab.getProperty(serverPrefsDoc, "MailArchiveCode.ServerSettingsClass", 0, "protocol");
        String user = (String) dab.getProperty(serverPrefsDoc, "MailArchiveCode.ServerSettingsClass", 0, "user");
        String password =
            (String) dab.getProperty(serverPrefsDoc, "MailArchiveCode.ServerSettingsClass", 0, "password");
        String folder = (String) dab.getProperty(serverPrefsDoc, "MailArchiveCode.ServerSettingsClass", 0, "folder");

        try {

            // Get a session. Use a blank Properties object.
            Properties props = new Properties();
            // necessary to work with Gmail
            props.put("mail.imap.partialfetch", "false");
            props.put("mail.imaps.partialfetch", "false");
            props.put("mail.store.protocol", protocol);

            Session session = Session.getDefaultInstance(props, null);
            // Get a Store object
            Store store = session.getStore(protocol);

            // Connect to the mail account
            store.connect(server, port, user, password);
            Folder fldr;
            // Specifically for GMAIL ...
            if (server.endsWith(".gmail.com")) {
                fldr = store.getDefaultFolder();
            }

            fldr = store.getFolder(folder);
            fldr.open(Folder.READ_ONLY);

            // Searches for mails not already read
            FlagTerm searchterms = new FlagTerm(new Flags(Flags.Flag.SEEN), false);
            Message[] messages = fldr.search(searchterms);
            nbMessages = messages.length;
            try {
                store.close();
            } catch (MessagingException e) {
                logger.debug(marker, "checkMails : Could not close connection", e);
            }
        } catch (AuthenticationFailedException e) {
            logger.warn(marker, "checkMails : ", e);
            return ConnectionErrors.AUTHENTICATION_FAILED.getCode();
        } catch (FolderNotFoundException e) {
            logger.warn(marker, "checkMails : ", e);
            return ConnectionErrors.FOLDER_NOT_FOUND.getCode();
        } catch (MessagingException e) {
            logger.warn(marker, "checkMails : ", e);
            if (e.getCause() instanceof java.net.UnknownHostException) {
                return ConnectionErrors.UNKNOWN_HOST.getCode();
            } else {
                return ConnectionErrors.CONNECTION_ERROR.getCode();
            }
        } catch (IllegalStateException e) {
            return ConnectionErrors.ILLEGAL_STATE.getCode();
        } catch (Throwable t) {
            logger.warn(marker, "checkMails : ", t);
            return ConnectionErrors.UNEXPECTED_EXCEPTION.getCode();
        }
        logger.debug(marker, "checkMails : ${nbMessages} to be read from $server:$port:$protocol:$user:$folder");
        return nbMessages;
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.component.mailarchive.MailArchive#loadMails(int, boolean)
     */
    @Override
    public synchronized boolean loadMails(int maxMailsNb)
    {
        try {
            if (!this.isInitialized) {
                initialize();
            }
            init();
        } catch (MailArchiveException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
            return false;
        } catch (InitializationException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
            return false;
        }

        try {
            /*
             * String loadingUserDoc = dab.getProperty(new DocumentReference("xwiki", spaceName, pageName),
             * classReference, propertyName); // TODO getLoadingUser(); if (dab.exists(getLoadingUser()) ||
             * !loadingUserDoc.getObject("XWiki.XWikiUsers")) { addDebug(
             * "Default Loading user set in Settings does not exist. Cannot load emails" ) return false; } // Get a
             * session. Use a blank Properties object. def props = new Properties(); // necessary to work with Gmail
             * props.put("mail.imap.partialfetch", "false"); props.put("mail.imaps.partialfetch", "false"); def session
             * = Session.getInstance(props); // Get a Store object def store = session.getStore(protocol); // Connect to
             * the mail account store.connect(server, user, pass) def fldr = store.getFolder(mailingListFolder)
             * fldr.open(Folder.READ_WRITE) // Searches for mails not already read def searchterms = new FlagTerm(new
             * Flags(Flags.Flag.SEEN), false) def messages = fldr.search(searchterms) def nbMessages = messages.size()
             * addDebug('Messages found : ' + nbMessages) if (nbMessages > 0) { // Load existing topics def nbTopics =
             * loadExistingTopics() addDebug("Number of existing TOPICS loaded from db : $nbTopics") // Load existing
             * mail ids def nbMails = loadExistingMessages()
             * addDebug("Number of existing EMAILS loaded from db : $nbMails") // Load mailing lists settings def
             * nbThreads = loadThreadsMap(threadsMap) def currmsg = 1 def nbLoaded = 0 // Load each message. If needed
             * delete them, and in any case set them as read - only if could be loaded for(mail in messages) { def
             * result = true try { addDebug("Loading mail ${currmsg}") try { result = loadMail(mail, true, false, null)
             * } catch (javax.mail.MessagingException me) { addDebug(
             * "Could not load mail normally due to MessagingException, trying to clone original email" ) // specific
             * case of unloadable mail ByteArrayOutputStream bos = new ByteArrayOutputStream(); mail.writeTo(bos);
             * bos.close(); SharedByteArrayInputStream bis = new SharedByteArrayInputStream(bos.toByteArray());
             * MimeMessage cmail = new MimeMessage(session, bis); bis.close(); result = loadMail(cmail, true, false,
             * null) } if (result[0]==true) { nbLoaded ++ if (withDelete==true) { mail.setFlag(Flags.Flag.DELETED,
             * true); } mail.setFlag(Flags.Flag.SEEN, true); } } catch (Exception e) {
             * addDebug("Failed to load mail with exception " + e.class + " " + e.getMessage()) e.printStackTrace()
             * addDebugStackTrace(e) } currmsg ++ } // for each message
             * addDebug("Loaded ${nbLoaded} messages over ${nbMessages}") try { // Update timeline info if needed if
             * (nbLoaded > 0) { //@TODO : check an option for time-line generation
             * addDebug("Refreshing time line information") URL url = new URL(xwiki
             * .getDocument("MailArchiveCode.TimeLineFeed").getExternalURL() + "?xpage=plain"); URLConnection uc = url
             * .openConnection() BufferedReader br = new BufferedReader( new InputStreamReader( uc.getInputStream()))
             * String inputLine=null while ((inputLine = br.readLine()) != null) addDebug("\t" + inputLine) br.close()
             * //println """{{include document="MailArchiveCode .TimeLineFeed" context="new" /}}"""
             * //addDebug(xwiki.getDocument('{{include document="MailArchiveCode.TimeLineFeed" context="new"
             * /}}').getRenderedContent()) } } catch (Throwable t) {
             * addDebug("Failed to update time-line feed with exception " + t.class + " " + t.getMessage()) } } // if
             * there are message if (withDelete) { fldr.close(true); } else { fldr.close(false); } store.close(); return
             * true;
             */

        } catch (Throwable e) {
            /*
             * addDebug("Failed to load emails with exception " + e.class+ " " + e.getMessage()) addDebugStackTrace(e)
             */
        } finally {
            // Release the lock in any case
            /*
             * release() System.out.println(getDebug());
             */
        }

        return true;

    }

    protected void init() throws MailArchiveException
    {
        loadExistingTopics();
        loadExistingMessages();
    }

    /**
     * Loads existing topics minimal information from database.
     * 
     * @return a map of existing topics, with key = topicId
     * @throws QueryException
     */
    public HashMap<String, TopicShortItem> loadExistingTopics() throws MailArchiveException
    {

        final HashMap<String, TopicShortItem> existingTopics = new HashMap<String, TopicShortItem>();
        List<Object[]> topics;

        String xwql =
            "select doc.fullName, topic.topicid, topic.subject " + "from Document doc, doc.object(" + SPACE_CODE
                + ".MailTopicClass) as  topic " + "where doc.space='" + SPACE_ITEMS + "'";

        try {
            topics = this.queryManager.createQuery(xwql, Query.XWQL).execute();

            for (Object[] topic : topics) {
                // map[topicId] = [fullname, subject]
                TopicShortItem shorttopic = new TopicShortItem((String) topic[0], (String) topic[2]);
                existingTopics.put((String) topic[1], shorttopic);
                logger.debug(marker, "Loaded topic " + topic[0] + " : " + shorttopic);
            }
        } catch (Exception e) {
            throw new MailArchiveException("Failed to load existing topics", e);
        }
        return existingTopics;
    }

    /**
     * Loads existing mails minimal information from database.
     * 
     * @return a map of existing mails, with key = messageId
     * @throws MailArchiveException
     */
    public HashMap<String, MailShortItem> loadExistingMessages() throws MailArchiveException
    {

        final HashMap<String, MailShortItem> existingMessages = new HashMap<String, MailShortItem>();
        List<Object[]> messages;

        try {
            String xwql =
                "select mail.messageid, mail.messagesubject, mail.topicid, doc.fullName "
                    + "from Document doc, doc.object(" + SPACE_CODE + ".MailClass) as  mail " + "where doc.space='"
                    + SPACE_ITEMS + "'";

            messages = this.queryManager.createQuery(xwql, Query.XWQL).execute();

            if (messages != null) {
                for (Object[] message : messages) {
                    if (message[0] != null && message[0] != "") {
                        // map[messageid] = [subject, topicid, fullName]
                        MailShortItem shortmail =
                            new MailShortItem((String) message[1], (String) message[2], (String) message[3]);
                        existingMessages.put((String) message[0], shortmail);
                        logger.debug(marker, "Loaded mail " + message[1] + " : " + shortmail);
                    } else {
                        logger.warn(marker, "Incorrect message object found in db for " + message[3]);
                    }
                }

            }

        } catch (Exception e) {
            throw new MailArchiveException("Failed to load existing messages", e);
        }

        return existingMessages;

    }

    /**
     * Loads the mailing-lists
     * 
     * @return
     * @throws MailArchiveException
     */
    public HashMap<String, String[]> loadMailingLists() throws MailArchiveException
    {
        final HashMap<String, String[]> lists = new HashMap<String, String[]>();

        String xwql =
            "select list.pattern, list.displayname, list.Tag from Document doc, doc.object('" + SPACE_CODE
                + ".ListsSettingsClass') as list where doc.space='" + SPACE_PREFS + "'";
        try {
            List<Object[]> props = this.queryManager.createQuery(xwql, Query.XWQL).execute();

            for (Object[] prop : props) {
                if (prop[0] != null && !"".equals(prop[0])) {
                    // map[pattern] = [displayname, Tag]
                    lists.put((String) prop[0], new String[] {(String) prop[1], (String) prop[2]});
                    logger.info(marker, "Loaded list " + prop[1] + " / " + prop[2] + " / " + prop[0]);
                } else {
                    logger.warn(marker, "Incorrect mailing-list found in db " + prop[1]);
                }
            }
        } catch (Exception e) {
            throw new MailArchiveException("Failed to load mailing-lists settings", e);
        }
        return lists;
    }

    /**
     * Loads mail types from database.
     * 
     * @return
     * @throws MailArchiveException
     */
    public List<MailType> loadMailTypes() throws MailArchiveException
    {
        List<MailType> mailTypes = new ArrayList<MailType>();

        String xwql =
            "select type.name, type.icon, type.patternList from Document doc, doc.object(" + SPACE_CODE
                + ".TypesSettingsClass) as type where doc.space='" + SPACE_PREFS + "'";
        try {
            List<Object[]> types = this.queryManager.createQuery(xwql, Query.XWQL).execute();

            for (Object[] type : types) {
                MailTypeImpl typeobj = new MailTypeImpl();
                typeobj.setName((String) type[0]);
                typeobj.setIcon((String) type[1]);

                HashMap<List<String>, String> patterns = new HashMap<List<String>, String>();
                String patternsList = (String) type[2];
                String[] splittedPatterns = patternsList.split("\n", -1);
                if (splittedPatterns.length % 2 == 0) {
                    for (int i = 0; i < splittedPatterns.length; i += 2) {
                        List<String> fields = Arrays.asList(splittedPatterns[i].split(",", 0));
                        patterns.put(fields, splittedPatterns[i + 1]);
                        logger.info(marker, "Loaded mail type " + type[0] + " applying pattern "
                            + splittedPatterns[i + 1] + " on fields " + fields);
                    }
                    typeobj.setPatterns(patterns);

                    mailTypes.add(typeobj);

                } else {
                    logger.warn("Invalid patterns list found for type " + type[0]);
                }
            }

        } catch (Exception e) {
            throw new MailArchiveException("Failed to load mail types settings", e);
        }

        return mailTypes;
    }

}
