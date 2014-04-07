/* ***** BEGIN LICENSE BLOCK *****
 * Version: MPL 1.1/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Mozilla Public License Version
 * 1.1 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the
 * License.
 *
 * The Original Code is part of dcm4che, an implementation of DICOM(TM) in
 * Java(TM), hosted at https://github.com/gunterze/dcm4che.
 *
 * The Initial Developer of the Original Code is
 * Agfa Healthcare.
 * Portions created by the Initial Developer are Copyright (C) 2011
 * the Initial Developer. All Rights Reserved.
 *
 * Contributor(s):
 * See @authors listed below
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either the GNU General Public License Version 2 or later (the "GPL"), or
 * the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the MPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the MPL, the GPL or the LGPL.
 *
 * ***** END LICENSE BLOCK ***** */

package org.cosysoft.dcm.tool;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.cosysoft.dcm.domain.RawStudy;
import org.dcm4che.data.Attributes;
import org.dcm4che.data.Tag;
import org.dcm4che.net.Association;
import org.dcm4che.net.DimseRSP;
import org.dcm4che.net.DimseRSPHandler;
import org.dcm4che.net.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * fixed finished 同步bug；转换为自己的 领域对象
 * 
 * @author Gunter Zeilinger <gunterze@gmail.com>
 * 
 */
public class SyncFutureDimseRSP extends DimseRSPHandler implements DimseRSP {

	public static final Logger logger = LoggerFactory
			.getLogger(SyncFutureDimseRSP.class);

	private static class Entry {
		final Attributes command;
		final Attributes dataset;
		Entry next;

		public Entry(Attributes command, Attributes dataset) {
			this.command = command;
			this.dataset = dataset;
		}
	}

	private Entry entry = new Entry(null, null);
	private AtomicBoolean finished = new AtomicBoolean(false);
	private int autoCancel;
	private IOException ex;

	public Entry getEntry() {
		return entry;
	}

	public void setEntry(Entry entry) {
		this.entry = entry;
	}

	public boolean isFinished() {
		return finished.get();
	}

	public void setFinished(boolean finished) {
		this.finished.set(finished);
	}

	public SyncFutureDimseRSP(int msgID) {
		super(msgID);
	}

	@Override
	public synchronized void onDimseRSP(Association as, Attributes cmd,
			Attributes data) {
		super.onDimseRSP(as, cmd, data);
		// logger.error(data==null?"null":data.toString());
		Entry last = entry;
		while (last.next != null)
			last = last.next;

		last.next = new Entry(cmd, data);
		if (Status.isPending(cmd.getInt(Tag.Status, 0))) {
			if (autoCancel > 0 && --autoCancel == 0)
				try {
					super.cancel(as);
				} catch (IOException e) {
					ex = e;
				}
		} else {
			finished.set(true);
		}
		notifyAll();
	}

	@Override
	public synchronized void onClose(Association as) {
		super.onClose(as);
		if (!finished.get()) {
			ex = as.getException();
			if (ex == null)
				ex = new IOException("Association to " + as.getRemoteAET()
						+ " released before receive of outstanding DIMSE RSP");
			notifyAll();
		}
	}

	public final void setAutoCancel(int autoCancel) {
		this.autoCancel = autoCancel;
	}

	@Override
	public void cancel(Association a) throws IOException {
		if (ex != null)
			throw ex;
		if (!finished.get())
			super.cancel(a);
	}

	public final Attributes getCommand() {
		return entry.command;
	}

	public final Attributes getDataset() {
		return entry.dataset;
	}

	public synchronized boolean next() throws IOException, InterruptedException {
		if (entry.next == null) {
			if (finished.get())
				return false;

			while (entry.next == null && ex == null)
				wait();

			if (ex != null)
				throw ex;
		}
		entry = entry.next;
		return true;
	}

	public List<RawStudy> getRawStudy() {
		if (!this.finished.get()) {
			throw new IllegalStateException(
					"you cann't call this method until the task finished!");
		}
		List<RawStudy> studies = new ArrayList<>();
		RawStudy s;
		Entry e = entry;
		while (e != null) {

			if (e.dataset != null) {
				s = new RawStudy();
				s.patientID = e.dataset.getString(Tag.PatientID, null);
				s.studyID = e.dataset.getString(Tag.StudyID, null);
				s.studyInstanceUID = e.dataset.getString(Tag.StudyInstanceUID,
						null);
				s.seriesInstanceUID = e.dataset.getString(
						Tag.SeriesInstanceUID, null);
				s.sopInstanceUID = e.dataset
						.getString(Tag.SOPInstanceUID, null);
				s.seriesCount = e.dataset.getInt(
						Tag.NumberOfStudyRelatedSeries, 0);
				s.imageCount = e.dataset.getInt(
						Tag.NumberOfSeriesRelatedInstances, 0);
				s.modalitiesInStudy = e.dataset
						.getString(Tag.ModalitiesInStudy);
				studies.add(s);
			}

			e = e.next;
		}

		return studies;
	}
}
