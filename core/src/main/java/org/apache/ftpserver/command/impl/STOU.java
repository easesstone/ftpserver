/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.ftpserver.command.impl;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.SocketException;

import org.apache.ftpserver.IODataConnectionFactory;
import org.apache.ftpserver.command.AbstractCommand;
import org.apache.ftpserver.ftplet.DataConnection;
import org.apache.ftpserver.ftplet.DataConnectionFactory;
import org.apache.ftpserver.ftplet.DefaultFtpReply;
import org.apache.ftpserver.ftplet.FileObject;
import org.apache.ftpserver.ftplet.FileSystemView;
import org.apache.ftpserver.ftplet.FtpException;
import org.apache.ftpserver.ftplet.FtpReply;
import org.apache.ftpserver.ftplet.FtpRequest;
import org.apache.ftpserver.impl.LocalizedFtpReply;
import org.apache.ftpserver.interfaces.FtpIoSession;
import org.apache.ftpserver.interfaces.FtpServerContext;
import org.apache.ftpserver.interfaces.ServerFtpStatistics;
import org.apache.ftpserver.util.IoUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <code>STOU &lt;CRLF&gt;</code><br>
 * 
 * This command behaves like STOR except that the resultant file is to be
 * created in the current directory under a name unique to that directory. The
 * 150 Transfer Started response must include the name generated, See RFC1123
 * section 4.1.2.9
 *
 * @author The Apache MINA Project (dev@mina.apache.org)
 * @version $Rev$, $Date$
 */
public class STOU extends AbstractCommand {

    private final Logger LOG = LoggerFactory.getLogger(STOU.class);

    /**
     * Execute command.
     */
    public void execute(final FtpIoSession session,
            final FtpServerContext context, final FtpRequest request)
            throws IOException, FtpException {

        try {
            // 24-10-2007 - added check if PORT or PASV is issued, see
            // https://issues.apache.org/jira/browse/FTPSERVER-110
            DataConnectionFactory connFactory = session.getDataConnection();
            if (connFactory instanceof IODataConnectionFactory) {
                InetAddress address = ((IODataConnectionFactory) connFactory)
                        .getInetAddress();
                if (address == null) {
                    session.write(new DefaultFtpReply(
                            FtpReply.REPLY_503_BAD_SEQUENCE_OF_COMMANDS,
                            "PORT or PASV must be issued first"));
                    return;
                }
            }

            // reset state variables
            session.resetState();

            String pathName = request.getArgument();

            // get filenames
            FileObject file = null;
            try {
                String filePrefix;
                if (pathName == null) {
                    filePrefix = "ftp.dat";
                } else {
                    FileObject dir = session.getFileSystemView().getFileObject(
                            pathName);
                    if (dir.isDirectory()) {
                        filePrefix = pathName + "/ftp.dat";
                    } else {
                        filePrefix = pathName;
                    }
                }

                file = session.getFileSystemView().getFileObject(filePrefix);
                if (file != null) {
                    file = getUniqueFile(session, file);
                }
            } catch (Exception ex) {
                LOG.debug("Exception getting file object", ex);
            }

            if (file == null) {
                session.write(LocalizedFtpReply.translate(session, request, context,
                        FtpReply.REPLY_550_REQUESTED_ACTION_NOT_TAKEN, "STOU",
                        null));
                return;
            }
            String fileName = file.getFullName();

            // check permission
            if (!file.hasWritePermission()) {
                session.write(LocalizedFtpReply.translate(session, request, context,
                        FtpReply.REPLY_550_REQUESTED_ACTION_NOT_TAKEN,
                        "STOU.permission", fileName));
                return;
            }

            // get data connection
            session.write(new DefaultFtpReply(
                    FtpReply.REPLY_150_FILE_STATUS_OKAY, "FILE: " + fileName));

            // get data from client
            boolean failure = false;
            OutputStream os = null;

            DataConnection dataConnection;
            try {
                dataConnection = session.getDataConnection().openConnection();
            } catch (Exception e) {
                LOG.debug("Exception getting the input data stream", e);
                session.write(LocalizedFtpReply.translate(session, request, context,
                        FtpReply.REPLY_425_CANT_OPEN_DATA_CONNECTION, "STOU",
                        fileName));
                return;
            }

            try {

                // open streams
                os = file.createOutputStream(0L);

                // transfer data
                long transSz = dataConnection.transferFromClient(os);

                // log message
                String userName = session.getUser().getName();
                LOG.info("File upload : " + userName + " - " + fileName);

                // notify the statistics component
                ServerFtpStatistics ftpStat = (ServerFtpStatistics) context
                        .getFtpStatistics();
                if (ftpStat != null) {
                    ftpStat.setUpload(session, file, transSz);
                }
            } catch (SocketException ex) {
                LOG.debug("Socket exception during data transfer", ex);
                failure = true;
                session.write(LocalizedFtpReply.translate(session, request, context,
                        FtpReply.REPLY_426_CONNECTION_CLOSED_TRANSFER_ABORTED,
                        "STOU", fileName));
            } catch (IOException ex) {
                LOG.debug("IOException during data transfer", ex);
                failure = true;
                session
                        .write(LocalizedFtpReply
                                .translate(
                                        session,
                                        request,
                                        context,
                                        FtpReply.REPLY_551_REQUESTED_ACTION_ABORTED_PAGE_TYPE_UNKNOWN,
                                        "STOU", fileName));
            } finally {
                IoUtils.close(os);
            }

            // if data transfer ok - send transfer complete message
            if (!failure) {
                session.write(LocalizedFtpReply.translate(session, request, context,
                        FtpReply.REPLY_226_CLOSING_DATA_CONNECTION, "STOU",
                        fileName));

            }
        } finally {
            session.getDataConnection().closeDataConnection();
        }

    }

    /**
     * Get unique file object.
     */
    protected FileObject getUniqueFile(FtpIoSession session, FileObject oldFile)
            throws FtpException {
        FileObject newFile = oldFile;
        FileSystemView fsView = session.getFileSystemView();
        String fileName = newFile.getFullName();
        while (newFile.doesExist()) {
            newFile = fsView.getFileObject(fileName + '.'
                    + System.currentTimeMillis());
            if (newFile == null) {
                break;
            }
        }
        return newFile;
    }

}