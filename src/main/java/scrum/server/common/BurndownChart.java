/*
 * Copyright 2009, 2010, 2011 Fabian Hager, Witoslaw Koczewsi <wi@koczewski.de>
 * 
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero
 * General Public License as published by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the
 * implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package scrum.server.common;

import ilarkesto.base.time.Date;
import ilarkesto.core.logging.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import org.jfree.chart.ChartUtilities;
import org.jfree.chart.JFreeChart;
import org.jfree.data.xy.DefaultXYDataset;

import scrum.client.common.WeekdaySelector;
import scrum.server.sprint.Sprint;
import scrum.server.sprint.SprintDaySnapshot;

public class BurndownChart extends Chart {

	private static final Log LOG = Log.get(BurndownChart.class);

	public static byte[] createBurndownChartAsByteArray(Sprint sprint, int width, int height) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		new BurndownChart().writeSprintBurndownChart(out, sprint, width, height);
		return out.toByteArray();
	}

	// public void writeProjectBurndownChart(OutputStream out, String projectId, int width, int height) {
	// Project project = projectDao.getById(projectId);
	// List<ProjectSprintSnapshot> snapshots = project.getSprintSnapshots();
	// project.getCurrentSprintSnapshot().update();
	//
	// writeProjectBurndownChart(out, snapshots, project.getBegin(), project.getEnd().addDays(1),
	// project.getFreeDaysAsWeekdaySelector(), width, height);
	// }

	public void writeChart(OutputStream out, String sprintId, int width, int height) {
		Sprint sprint = sprintDao.getById(sprintId);
		if (sprint == null) throw new IllegalArgumentException("Sprint " + sprintId + " does not exist.");
		writeSprintBurndownChart(out, sprint, width, height);
	}

	public void writeSprintBurndownChart(OutputStream out, Sprint sprint, int width, int height) {
		List<SprintDaySnapshot> snapshots = sprint.getDaySnapshots();
		if (snapshots.isEmpty()) {
			Date date = Date.today();
			date = Date.latest(date, sprint.getBegin());
			date = Date.earliest(date, sprint.getEnd());
			sprint.getDaySnapshot(date).updateWithCurrentSprint();
			snapshots = sprint.getDaySnapshots();
		}

		WeekdaySelector freeDays = sprint.getProject().getFreeDaysAsWeekdaySelector();

		writeSprintBurndownChart(out, snapshots, sprint.getBegin(), sprint.getEnd(), freeDays, width, height);
	}

	// private void writeProjectBurndownChart(OutputStream out, List<ProjectSprintSnapshot> snapshots, Date
	// firstDay,
	// Date lastDay, WeekdaySelector freeDays, int width, int height) {
	// List<BurndownSnapshot> burndownSnapshots = new ArrayList<BurndownSnapshot>(snapshots);
	// DefaultXYDataset data = createSprintBurndownChartDataset(burndownSnapshots, firstDay, lastDay);
	// double tick = 1.0;
	// double max = getMaximum(data);
	//
	// while (max / tick > 25) {
	// tick *= 2;
	// if (max / tick <= 25) break;
	// tick *= 2.5;
	// if (max / tick <= 25) break;
	// tick *= 2;
	// }
	//
	// JFreeChart chart = createSprintBurndownChart(data, "Date", "Work", firstDay, lastDay, 10, 30, max *
	// 1.1, tick,
	// freeDays);
	// try {
	// ChartUtilities.writeScaledChartAsPNG(out, chart, width, height, 1, 1);
	// } catch (IOException e) {
	// throw new RuntimeException(e);
	// }
	// }

	static void writeSprintBurndownChart(OutputStream out, List<? extends BurndownSnapshot> snapshots, Date firstDay,
			Date lastDay, WeekdaySelector freeDays, int width, int height) {
		LOG.debug("Creating burndown chart:", snapshots.size(), "snapshots from", firstDay, "to", lastDay, "(" + width
				+ "x" + height + " px)");

		int dayCount = firstDay.getPeriodTo(lastDay).toDays();
		int dateMarkTickUnit = 1;
		float widthPerDay = (float) width / (float) dayCount * dateMarkTickUnit;
		while (widthPerDay < 20) {
			dateMarkTickUnit++;
			widthPerDay = (float) width / (float) dayCount * dateMarkTickUnit;
		}

		List<BurndownSnapshot> burndownSnapshots = new ArrayList<BurndownSnapshot>(snapshots);
		DefaultXYDataset data = createSprintBurndownChartDataset(burndownSnapshots, firstDay, lastDay, freeDays);
		JFreeChart chart = createChart(firstDay, lastDay, dateMarkTickUnit, widthPerDay, data, getMaximum(data), height);
		try {
			ChartUtilities.writeScaledChartAsPNG(out, chart, width, height, 1, 1);
			out.flush();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	static DefaultXYDataset createSprintBurndownChartDataset(final List<BurndownSnapshot> snapshots,
			final Date firstDay, final Date lastDay, final WeekdaySelector freeDays) {

		ChartDataFactory factory = new ChartDataFactory();
		factory.createDataset(snapshots, firstDay, lastDay, freeDays);
		return factory.getDataset();
	}

	static class ChartDataFactory {

		List<Double> mainDates = new ArrayList<Double>();
		List<Double> mainValues = new ArrayList<Double>();

		List<Double> extrapolationDates = new ArrayList<Double>();
		List<Double> extrapolationValues = new ArrayList<Double>();

		List<Double> idealDates = new ArrayList<Double>();
		List<Double> idealValues = new ArrayList<Double>();

		List<BurndownSnapshot> snapshots;
		WeekdaySelector freeDays;

		Date date;
		long millisBegin;
		long millisEnd;
		boolean freeDay;
		BurndownSnapshot snapshot;
		boolean workStarted;
		boolean workFinished;

		int totalBurned;
		int totalBefore;
		int totalAfter;
		int burned;
		int jump;
		double totalRemaining;
		int workDays;
		double burnPerDay;
		double idealRemaining;
		double idealBurnPerDay;
		int totalWorkDays = 0;
		boolean extrapolationFinished;

		DefaultXYDataset dataset;

		public void createDataset(final List<BurndownSnapshot> snapshots, final Date firstDay, final Date lastDay,
				final WeekdaySelector freeDays) {
			this.snapshots = snapshots;
			this.freeDays = freeDays;

			date = firstDay;
			while (date.isBeforeOrSame(lastDay)) {
				if (!freeDays.isFree(date.getWeekday().getDayOfWeek())) totalWorkDays++;
				date = date.nextDay();
			}

			setDate(firstDay);
			while (true) {
				if (!workFinished) {
					burned = snapshot.getBurnedWork() - totalBurned;
					totalBurned = snapshot.getBurnedWork();
					totalAfter = snapshot.getRemainingWork();
					jump = totalAfter - totalBefore + burned;
				}

				// System.out.println(date + " totalBefore=" + totalBefore + " totalAfter=" + totalAfter +
				// " jump=" + jump
				// + " burned=" + burned + " -> " + snapshot);
				if (workFinished) {
					processSuffix();
				} else if (workStarted) {
					processCenter();
				} else {
					processPrefix();
				}
				if (date.equals(lastDay)) break;
				setDate(date.nextDay());
				totalBefore = totalAfter;
			}

			dataset = new DefaultXYDataset();
			dataset.addSeries("Main", toArray(mainDates, mainValues));
			dataset.addSeries("Extrapolation", toArray(extrapolationDates, extrapolationValues));
			dataset.addSeries("Ideal", toArray(idealDates, idealValues));
		}

		private void setDate(Date newDate) {
			date = newDate;
			millisBegin = date.toMillis();
			millisEnd = date.nextDay().toMillis();
			freeDay = freeDays.isFree(date.getWeekday().getDayOfWeek());
			if (!workFinished) snapshot = getSnapshot();
		}

		private void processPrefix() {
			if (totalAfter > 0 || totalBurned > 0) {
				workStarted = true;
				idealRemaining = totalAfter + burned;
				idealDates.add((double) millisBegin);
				idealValues.add(idealRemaining);

				if (totalWorkDays > 0) {
					idealBurnPerDay = (double) jump / (double) totalWorkDays;
					// System.out.println("*** jump:" + jump + " totalWorkDays:" + totalWorkDays +
					// " idealBurnPerDay:"
					// + idealBurnPerDay);
				}

				processCenter();
				return;
			}
			totalWorkDays--;
		}

		private void processCenter() {
			mainDates.add((double) millisBegin);
			mainValues.add((double) totalBefore);
			if (jump != 0) {
				mainDates.add((double) millisBegin);
				mainValues.add((double) totalBefore + jump);
			}
			mainDates.add((double) millisEnd);
			mainValues.add((double) totalAfter);

			if (!freeDay) {
				workDays++;
				idealRemaining -= idealBurnPerDay;
			}

			idealDates.add((double) millisEnd);
			idealValues.add(idealRemaining);
		}

		private void processSuffix() {
			if (!freeDay) {
				totalRemaining -= burnPerDay;
				idealRemaining -= idealBurnPerDay;
			}
			if (!extrapolationFinished) {
				extrapolationDates.add((double) millisEnd);
				extrapolationValues.add(totalRemaining);
			}
			idealDates.add((double) millisEnd);
			idealValues.add(idealRemaining);
			if (totalRemaining <= 0) extrapolationFinished = true;
		}

		private BurndownSnapshot getSnapshot() {
			for (BurndownSnapshot snapshot : snapshots) {
				if (snapshot.getDate().equals(date)) return snapshot;
			}
			workFinished = true;
			totalRemaining = totalAfter;
			burnPerDay = (double) totalBurned / (double) workDays;
			// System.out.println("***** totalBurned:" + totalBurned + " workDays:" + workDays +
			// " burnPerDay:"
			// + burnPerDay);
			extrapolationDates.add((double) millisBegin);
			extrapolationValues.add(totalRemaining);
			return null;
		}

		public DefaultXYDataset getDataset() {
			return dataset;
		}

	}

}
