package com.ubtech.util;

import java.text.DateFormat;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;

/**
 * @since 1.0
 */
public class TimeUtils {
	// private int weeks = 0;// ����ȫ�ֿ��� ��һ�ܣ����ܣ���һ�ܵ������仯
	// private int MaxDate; // һ���������?
	// private int MaxYear; // һ���������?

	public static final String YYYY_MM_DD = "yyyy-MM-dd";
	public static final String YYYY_MM_DD_HH_MM_SS = "yyyy-MM-dd HH:mm:ss";
	public static final String DD_MM_YYYY = "dd-MM-yyyy";
	public static final String MM_DD_YYYY = "MM-dd-yyyy";
	public static final String HH_MM_SS = "HH:mm:ss";
	public static final String YYYYMMDD_HHMMSS = "yyyyMMdd HHmmss";

	// ÿ������(������)
	static int daysInMonth[] = { 31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31 };
	// ����������·�?
	static final int MONTH_FEBRUARY = 2;
	public static final int PRECISE_YEAR = 1;
	public static final int PRECISE_MONTH = 2;
	public static final int PRECISE_DAY = 3;
	public static final int PRECISE_HOUR = 4;
	public static final int PRECISE_MINUTE = 5;
	public static final int PRECISE_SECOND = 6;
	public static final int PRECISE_MilliSECOND = 7;

	/**
	 * ��õ�ǰ���
	 * 
	 * @return
	 */
	public static int getYear() {
		return Calendar.getInstance().get(Calendar.YEAR);
	}

	/**
	 * ��õ�ǰ�·�?
	 * 
	 * @return
	 */
	public static int getMonth() {
		return Calendar.getInstance().get(Calendar.MONTH) + 1;
	}

	/**
	 * ��ý����ڱ���ĵڼ���
	 * 
	 * @return
	 */
	public static int getDayOfYear() {
		return Calendar.getInstance().get(Calendar.DAY_OF_YEAR);
	}

	/**
	 * �ж��Ƿ����£����ڼ��㵱ǰʱ����Ϸ��Ӻ��ʱ��
	 * 
	 * @param year
	 *            ���?
	 * @return
	 */
	public static boolean isLeapYear(int year) {
		// �ܱ�100����, ���ܱ�400���������?, ��������.
		// �ܱ�100����, Ҳ�ܱ�400���������?, ������.
		if ((year % 100) == 0) {
			return ((year % 400) == 0);
		} else // ���ܱ�100����, �ܱ�4���������������?.
		{
			return ((year % 4) == 0);
		}
	}

	/**
	 * ��ý����ڱ��µĵڼ���?(��õ�ǰ��?)
	 * 
	 * @return
	 */
	public static int getDayOfMonth() {
		return Calendar.getInstance().get(Calendar.DAY_OF_MONTH);
	}

	/**
	 * ��ý����ڱ��ܵĵڼ���?
	 * 
	 * @return
	 */
	public static int getDayOfWeek() {
		return Calendar.getInstance().get(Calendar.DAY_OF_WEEK);
	}

	/**
	 * ��ý���������µĵڼ���
	 * 
	 * @return
	 */
	public static int getWeekOfMonth() {
		return Calendar.getInstance().get(Calendar.DAY_OF_WEEK_IN_MONTH);
	}

	/**
	 * ��ð���������? ����
	 * 
	 * @return
	 * @throws Exception
	 */
	public static Date getTimeYearNext(String format) {
		Calendar calendar = Calendar.getInstance();
		calendar.add(Calendar.DAY_OF_YEAR, 183);
		String date = new SimpleDateFormat(format).format(calendar.getTime());
		return getDateParse(date, format);
	}

	/**
	 * ������ת�����ַ���
	 * 
	 * @param dateTime
	 * @return
	 */
	public static String convertDateToString(Date dateTime, String format) {
		SimpleDateFormat df = new SimpleDateFormat(format);
		return df.format(dateTime);
	}

	/**
	 * �õ��������ڼ�ļ������
	 * 
	 * @param sj1
	 * @param sj2
	 * @param format
	 * @return
	 */
	public static String getTwoDay(String sj1, String sj2, String format) {
		SimpleDateFormat myFormatter = new SimpleDateFormat(format);
		long day = 0;
		try {
			Date date = myFormatter.parse(sj1);
			Date mydate = myFormatter.parse(sj2);
			day = (date.getTime() - mydate.getTime()) / (24 * 60 * 60 * 1000);
		} catch (Exception e) {
			return "";
		}
		return day + "";
	}

	/**
	 * ����һ�����ڣ����������ڼ����ַ���
	 *
	 * @param args
	 * @return
	 */
	public static String getWeek(String args) {
		// ��ת��Ϊʱ��
		Date date = TimeUtils.strToDate(args, TimeUtils.YYYY_MM_DD);
		Calendar c = Calendar.getInstance();
		c.setTime(date);
		// int hour=c.get(Calendar.DAY_OF_WEEK);
		// hour�д�ľ������ڼ��ˣ��䷶�? 1~7
		// 1=������ 7=����������������
		return new SimpleDateFormat("EEEE").format(c.getTime());
	}

	/**
	 * ����ʱ���ʽ�ַ���ת��Ϊʱ��? yyyy-MM-dd
	 *
	 * @param args
	 * @return
	 */
	public static Date strToDate(String args, String format) {
		return new SimpleDateFormat(format).parse(args, new ParsePosition(0));
	}

	/**
	 * ����ʱ��֮�������?
	 *
	 * @param date1
	 * @param date2
	 * @param format
	 * @return
	 */
	public static long getDays(String args1, String args2, String format) {
		if (args1 == null || args1.equals(""))
			return 0;
		if (args2 == null || args2.equals(""))
			return 0;
		// ת��Ϊ��׼ʱ��
		SimpleDateFormat myFormatter = new SimpleDateFormat(format);
		Date date = null;
		Date mydate = null;
		try {
			date = myFormatter.parse(args1);
			mydate = myFormatter.parse(args2);
		} catch (Exception e) {
		}
		long day = (date.getTime() - mydate.getTime()) / (24 * 60 * 60 * 1000);
		return day;
	}

	/**
	 * ���㵱�����һ��?,�����ַ���
	 * 
	 * @param format
	 * @return
	 */
	public static String getDefaultDay(String format) {
		String str = "";
		SimpleDateFormat sdf = new SimpleDateFormat(format);

		Calendar lastDate = Calendar.getInstance();
		lastDate.set(Calendar.DATE, 1);// ��Ϊ��ǰ�µ�1��
		lastDate.add(Calendar.MONTH, 1);// ��һ���£���Ϊ���µ�1��
		lastDate.add(Calendar.DATE, -1);// ��ȥһ�죬��Ϊ�������һ��?

		str = sdf.format(lastDate.getTime());
		return str;
	}

	/**
	 * ���µ�һ��
	 * 
	 * @param format
	 * @return
	 */
	public static String getPreviousMonthFirst(String format) {
		Calendar lastDate = Calendar.getInstance();
		lastDate.set(Calendar.DATE, 1);// ��Ϊ��ǰ�µ�1��
		lastDate.add(Calendar.MONTH, -1);// ��һ���£���Ϊ���µ�1��
		// lastDate.add(Calendar.DATE,-1);//��ȥһ�죬��Ϊ�������һ��?
		String date = new SimpleDateFormat(format).format(lastDate.getTime());
		return date;
	}

	/**
	 * �������һ��?
	 * 
	 * @param num
	 * @param format
	 * @return
	 */
	public static String getPreviousMonthEnd(String format) {
		Calendar lastDate = Calendar.getInstance();
		lastDate.set(Calendar.DATE, 1);// ��Ϊ��ǰ�µ�1��
		// lastDate.add(Calendar.MONTH, num+1);// ��һ���£���Ϊ���µ�1��
		lastDate.add(Calendar.DATE, -1);// ��ȥһ�죬��Ϊ�������һ��?
		String date = new SimpleDateFormat(format).format(lastDate.getTime());
		return date;
	}

	/**
	 * ĳ�µ�һ��
	 * 
	 * @param num
	 * @param format
	 * @return
	 */
	public static String getMonthFirst(int num, String format) {
		Calendar lastDate = Calendar.getInstance();
		lastDate.set(Calendar.DATE, 1);// ��Ϊ��ǰ�µ�1��
		lastDate.add(Calendar.MONTH, num);// ��һ���£���Ϊ���µ�1��
		// lastDate.add(Calendar.DATE,-1);//��ȥһ�죬��Ϊ�������һ��?
		String date = new SimpleDateFormat(format).format(lastDate.getTime());
		return date;
	}

	/**
	 * ĳ�����һ��?
	 * 
	 * @param num
	 * @param format
	 * @return
	 */
	public static String getMonthEnd(int num, String format) {
		Calendar lastDate = Calendar.getInstance();
		lastDate.set(Calendar.DATE, 1);// ��Ϊ��ǰ�µ�1��
		lastDate.add(Calendar.MONTH, num + 1);// ��һ���£���Ϊ���µ�1��
		lastDate.add(Calendar.DATE, -1);// ��ȥһ�죬��Ϊ�������һ��?
		String date = new SimpleDateFormat(format).format(lastDate.getTime());
		return date;
	}

	/**
	 * ��ȡ���µ�һ��
	 * 
	 * @param format
	 * @return
	 */
	public static String getFirstDayOfMonth(String format) {
		String str = "";
		SimpleDateFormat sdf = new SimpleDateFormat(format);

		Calendar lastDate = Calendar.getInstance();
		lastDate.set(Calendar.DATE, 1);// ��Ϊ��ǰ�µ�1��
		str = sdf.format(lastDate.getTime());
		return str;
	}

	/**
	 * ��ñ��������յ�����?
	 * 
	 * @return
	 */
	public static String getCurrentWeekday() {
		// weeks = 0;
		int mondayPlus = getMondayPlus();
		GregorianCalendar currentDate = new GregorianCalendar();
		currentDate.add(GregorianCalendar.DATE, mondayPlus + 6);
		Date monday = currentDate.getTime();

		DateFormat df = DateFormat.getDateInstance();
		String preMonday = df.format(monday);
		return preMonday;
	}

	/**
	 * ��ȡ����ʱ��
	 * 
	 * @param format
	 * @return
	 */
	public static String getNowTime(String format) {
		Date now = new Date();
		SimpleDateFormat dateFormat = new SimpleDateFormat(format);// ���Է�����޸����ڸ��?
		String hehe = dateFormat.format(now);
		return hehe;
	}

	/**
	 * ��õ�ǰ�����뱾������������?
	 * 
	 * @return
	 */
	public static int getMondayPlus() {
		Calendar cd = Calendar.getInstance();
		// ��ý�����һ�ܵĵڼ��죬�������ǵ�һ�죬���ڶ��ǵڶ���?......
		int dayOfWeek = cd.get(Calendar.DAY_OF_WEEK) - 1; // ��Ϊ���й����һ��Ϊ��һ�����������1
		if (dayOfWeek == 1) {
			return 0;
		} else {
			return 1 - dayOfWeek;
		}
	}

	/**
	 * ��ñ���һ������?
	 * 
	 * @return
	 */
	public static String getMondayOFWeek() {
		// weeks = 0;
		int mondayPlus = getMondayPlus();
		GregorianCalendar currentDate = new GregorianCalendar();
		currentDate.add(GregorianCalendar.DATE, mondayPlus);
		Date monday = currentDate.getTime();
		DateFormat df = DateFormat.getDateInstance();
		String preMonday = df.format(monday);
		return preMonday;
	}

	/**
	 * ���ĳ������?
	 * 
	 * @param args
	 * @param separator
	 *            "/" "-"
	 * @return
	 */
	public static int getMonthDay(String args, String separator) {
		String[] str = args.split(separator);
		Calendar calendar = Calendar.getInstance();
		int months = Integer.parseInt(str[1]);
		int years = Integer.parseInt(str[0]);
		int days = 1;
		calendar.set(years, months - 1, days);
		int day = calendar.getActualMaximum(calendar.DAY_OF_MONTH);
		return day;
	}

	/**
	 * �����ַ�ʱ�����������?
	 * 
	 * @param argsBegin
	 * @param argsEnd
	 * @param format
	 *            "yyyy-MM-dd"
	 * @return
	 */
	public static int dateDiff(String argsBegin, String argsEnd, String format) {
		Date dateBegin = getDateParse(argsBegin, format);
		Date dateEnd = getDateParse(argsEnd, format);
		long timeBegin = dateBegin.getTime();
		long timeEnd = dateEnd.getTime();
		long diff = Math.abs(timeBegin - timeEnd);

		diff /= 3600 * 1000 * 24;
		return (int) diff;
	}

	/**
	 * �ַ���תʱ�� ��ʽ
	 * 
	 * @param args
	 * @param format
	 * @return
	 */
	public static Date getDateParse(String args, String format) {
		return new SimpleDateFormat(format).parse(args, new ParsePosition(0));
	}

	/**
	 * �����������ʱ��? -1 �� 1
	 * 
	 * @param num
	 * @return String "yyyy-MM-dd HH:mm:ss"
	 */

	public static String getYesterday(int num, String format) {
		SimpleDateFormat sdf = new SimpleDateFormat(format);
		Calendar calendar = Calendar.getInstance();
		GregorianCalendar gc = new GregorianCalendar(
				calendar.get(Calendar.YEAR), calendar.get(Calendar.MARCH),
				calendar.get(Calendar.DAY_OF_MONTH));
		gc.add(Calendar.DATE, num);
		Date date = gc.getTime();
		String str = sdf.format(date);
		return str;
	}

	/**
	 * ��ȡ��Ե�ǰ��ĳ����һ����?
	 * 
	 * @param num
	 * @param format
	 * @return
	 */
	public static String getWeekday(int num, String format) {
		Calendar calendar = Calendar.getInstance();
		calendar.add(Calendar.DATE, num * 7);
		calendar.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY);
		return new SimpleDateFormat(format).format(calendar.getTime());
	}

	/**
	 * ��ȡ��Ե�ǰ��ĳ����������?
	 * 
	 * @param num
	 *            -1 ��һ�� 0 ���� 1 ��һ��
	 * @param format
	 *            "yyyy-MM-dd"
	 * @return
	 */
	public static String getWeekSunday(int num, String format) {
		Calendar calendar = Calendar.getInstance();
		calendar.setFirstDayOfWeek(Calendar.MONDAY);
		calendar.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY);
		calendar.add(Calendar.WEEK_OF_MONTH, num);
		return new SimpleDateFormat(format).format(calendar.getTime());
	}

	/**
	 * ��ȡ����һ����
	 * 
	 * @param num
	 * @param format
	 * @return
	 */
	public static String getPreviousWeekday(String format) {
		Calendar calendar = Calendar.getInstance();
		calendar.add(Calendar.DATE, -1 * 7);
		calendar.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY);
		return new SimpleDateFormat(format).format(calendar.getTime());
	}

	/**
	 * ��ȡ����������
	 * @param format
	 *            "yyyy-MM-dd"
	 * @return
	 */
	public static String getPreviousWeekSunday(String format) {
		Calendar calendar = Calendar.getInstance();
		calendar.setFirstDayOfWeek(Calendar.MONDAY);
		calendar.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY);
		calendar.add(Calendar.WEEK_OF_MONTH, -1);
		return new SimpleDateFormat(format).format(calendar.getTime());
	}

	/**
	 * ��ȡ����һ����
	 * 
	 * @param format
	 * @return
	 */
	public static String getNextMonday(String format) {
		Calendar calendar = Calendar.getInstance();
		calendar.add(Calendar.DATE, 1 * 7);
		calendar.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY);
		return new SimpleDateFormat(format).format(calendar.getTime());
	}

	/**
	 * ��ȡ����������
	 * 
	 * @param format
	 *            "yyyy-MM-dd"
	 * @return
	 */
	public static String getNextSunday(String format) {
		Calendar calendar = Calendar.getInstance();
		calendar.setFirstDayOfWeek(Calendar.MONDAY);
		calendar.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY);
		calendar.add(Calendar.WEEK_OF_MONTH, 1);
		return new SimpleDateFormat(format).format(calendar.getTime());
	}

	/**
	 * ĳ���µ�һ��
	 * 
	 * @param format
	 * @return
	 */
	public static String getNextMonthFirst(String format) {
		Calendar lastDate = Calendar.getInstance();
		lastDate.set(Calendar.DATE, 1);// ��Ϊ��ǰ�µ�1��
		lastDate.add(Calendar.MONTH, 1);//
		return new SimpleDateFormat(format).format(lastDate.getTime());
	}

	/**
	 * ĳ�������һ��?
	 * 
	 * @param format
	 * @return
	 */
	public static String getNextMonthEnd(String format) {
		Calendar lastDate = Calendar.getInstance();
		lastDate.set(Calendar.DATE, 1);// ��Ϊ��ǰ�µ�1��
		lastDate.add(Calendar.MONTH, 2);
		lastDate.add(Calendar.DATE, -1);// ��ȥһ�죬��Ϊ�������һ��?
		return new SimpleDateFormat(format).format(lastDate.getTime());
	}

	/**
	 * ���һ�����������ܵ����ڼ�������?
	 * 
	 * @param args
	 * @param num
	 * @param format
	 * @return
	 */
	public static String getWeek(String args, String num, String format) {
		Date date = TimeUtils.getDateParse(args, TimeUtils.YYYY_MM_DD);
		Calendar calendar = Calendar.getInstance();
		calendar.setTime(date);
		if ("1".equals(num)) {
			calendar.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY);
		} else if ("2".equals(num)) {
			calendar.set(Calendar.DAY_OF_WEEK, Calendar.TUESDAY);
		} else if ("3".equals(num)) {
			calendar.set(Calendar.DAY_OF_WEEK, Calendar.WEDNESDAY);
		} else if ("4".equals(num)) {
			calendar.set(Calendar.DAY_OF_WEEK, Calendar.THURSDAY);
		} else if ("5".equals(num)) {
			calendar.set(Calendar.DAY_OF_WEEK, Calendar.FRIDAY);
		} else if ("6".equals(num)) {
			calendar.set(Calendar.DAY_OF_WEEK, Calendar.SATURDAY);
		} else if ("0".equals(num)) {
			calendar.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY);
		}
		return new SimpleDateFormat(format).format(calendar.getTime());
	}

	/**
	 * �Ƚ�����ʱ��Ĵ��? args1 > args2 ����true
	 * 
	 * @param args1
	 * @param args2
	 * @param format
	 * @return
	 */
	public static boolean getTimeStepSize(String args1, String args2,
			String format) {
		Date date1 = getDateParse(args1, format);
		Date date2 = getDateParse(args2, format);
		long time1 = date1.getTime();
		long time2 = date2.getTime();
		return time1 > time2 ? true : false;
	}

	/**
	 * ����
	 * 
	 * @param args
	 */
	public static void main(String[] args) {
		TimeUtils tt = new TimeUtils();
		System.out.println("��ȡ��������:" + tt.getNowTime("yyyy-MM-dd"));
		// System.out.println("��ȡ����һ����:" + tt.getMondayOFWeek());
		// System.out.println("��ȡ�����յ�����~:" + tt.getCurrentWeekday());
		// System.out.println("��ȡ����һ����:" + tt.getPreviousWeekday("yyyy-MM-dd"));
		// System.out.println("��ȡ����������:" +
		// tt.getPreviousWeekSunday("yyyy-MM-dd"));
		// System.out.println("��ȡ����һ����:" + tt.getWeekday(-1, "yyyy-MM-dd"));
		// System.out.println("��ȡ����������:" + tt.getWeekSunday(-1, "yyyy-MM-dd"));
		// System.out.println("��ȡ����һ����:" + tt.getNextMonday("yyyy-MM-dd"));
		// System.out.println("��ȡ����������:" + tt.getNextSunday("yyyy-MM-dd"));
		// System.out.println("��ȡ���µ�һ������:" + tt.getFirstDayOfMonth());
		// System.out.println("��ȡ�������һ������?:" + tt.getDefaultDay());
		// System.out.println("��ȡ���µ�һ������:" +
		// tt.getPreviousMonthFirst("yyyy-MM-dd"));
		// System.out.println("��ȡ�������һ�������:" +
		// tt.getPreviousMonthEnd("yyyy-MM-dd"));
		// System.out.println("��ȡĳ�µ�һ������:" + tt.getMonthFirst(0, "yyyy-MM-dd"));
		// System.out.println("��ȡĳ�����һ�������:" + tt.getMonthEnd(0, "yyyy-MM-dd"));
		// System.out.println("��ȡ���µ�һ������:" +
		// tt.getNextMonthFirst("yyyy-MM-dd"));
		// System.out.println("��ȡ�������һ������?:" + tt.getNextMonthEnd("yyyy-MM-dd"));
		// System.out.println("��ȡ����ĵ�һ������?:" + tt.getCurrentYearFirst());
		// System.out.println("��ȡ�������һ������?:" + tt.getCurrentYearEnd());
		// System.out.println("��ȡȥ��ĵ�һ������?:" + tt.getPreviousYearFirst());
		// System.out.println("��ȡȥ������һ������:" + tt.getPreviousYearEnd());
		// System.out.println("��ȡ�����һ������?:" + tt.getNextYearFirst());
		// System.out.println("��ȡ�������һ������?:" + tt.getNextYearEnd());
		// System.out.println("��ȡ�����ȵ�һ��:" + tt.getThisSeasonFirstTime(11));
		// System.out.println("��ȡ���������һ��?:" + tt.getThisSeasonFinallyTime(11));
		// System.out.println("��ȡ��������֮��������2008-12-1~2008-9.29:"+
		// TimeUtil.getTwoDay("2008-12-1", "2008-9-29"));
		// System.out.println("��ȡ��ǰ�µĵڼ��ܣ�" + tt.getWeekOfMonth());
		// System.out.println("��ȡ��ǰ��ݣ�?" + tt.getYear());
		// System.out.println("��ȡ��ǰ�·ݣ�" + tt.getMonth());
		// System.out.println("��ȡ�����ڱ���ĵڼ���?" + tt.getDayOfYear());
		// System.out.println("��ý����ڱ��µĵڼ���?(��õ�ǰ��?)��" + tt.getDayOfMonth());
		// System.out.println("��ý����ڱ��ܵĵڼ���?" + tt.getDayOfWeek());
		// System.out.println(tt.convertDateToString(new Date(),"yyyy-MM-dd"));
		// System.out.println("      ----"+tt.getTimeYearNext("YYYY-MM-dd"));
		// System.out.println(dateDiff("2012-02-01","2012-03-01","yyyy-MM-dd"));
		// System.out.println("�ַ���תʱ��"+getDateParse("2012-02-01",
		// "yyyy-MM-dd"));
		// System.out.println(getYesterday(2, "yyyy-MM-dd HH:mm:ss"));
		// System.out.println(getWeek("2012-03-03"));
		// System.out.println("���һ�����������ܵ����ڼ�������?  "+getWeek("2012-03-04","0",TimeUtil.YYYY_MM_DD_HH_MM_SS));
		// System.out.println(getTimeStepSize("2012-02-05 12:25:50","2012-02-05 13:00:50","yyyy-MM-dd HH:mm:ss"));
	}
}
