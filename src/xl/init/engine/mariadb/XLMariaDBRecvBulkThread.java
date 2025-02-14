package xl.init.engine.mariadb;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Vector;

import xl.init.conf.XLConf;
import xl.init.logger.XLLogger;
import xl.lib.common.XLCons;
import xl.init.conn.XLMariaDBConnection;
import xl.init.info.XLDBMSInfo;
import xl.init.info.XLDataQ;
import xl.init.info.XLDicInfoCons;
import xl.init.info.XLJobColInfo;
import xl.init.info.XLJobRunPol;
import xl.init.main.XLOGCons;
import xl.init.util.XLException;
import xl.init.util.XLUtil;


/**
 * 
 * @author cksohn
 * 
 * cksohn - BULK mode oracle sqlldr
 *
 */

public class XLMariaDBRecvBulkThread extends Thread {

	
	private XLDataQ dataQ = null;
	private XLJobRunPol jobRunPol = null;
	
	// 테이블 컬럼의 정보
	private Vector<XLJobColInfo> vtColInfo = null;
	
	// gssg - xl m2m bulk mode 지원
	// private XLOracleConnection oraConnObj = null;
	// private OraclePreparedStatement pstmtSelect = null;
	private XLMariaDBConnection mariaDBConnObj = null;
	private PreparedStatement pstmtSelect = null;
	
	
	// cksohn - BULK mode oracle sqlldr
	private RandomAccessFile pipe = null;
	
	public boolean isWait = false;
	
	
	private String errMsg = null;
	
	private String logHead = "";
	
	public XLMariaDBRecvBulkThread(XLJobRunPol jobRunPol) {
		super();
		this.jobRunPol = jobRunPol;
		this.dataQ = this.jobRunPol.getDataQ();
		
		// 대상 테이블의 컬럼 정보
		this.vtColInfo = this.jobRunPol.getTableInfo().getVtColInfo();
		
		this.logHead = "[" + this.jobRunPol.getPolName() + "][RECV BULK]";
	}

	@Override
	public void run(){
		
		ResultSet rs = null;
		
		
		long stime = 0; // 시작 시간
		long etime = 0; // 종료 시간
		
		try {
			
			XLLogger.outputInfoLog("");
			XLLogger.outputInfoLog(this.logHead + " Starting RecvThread(Direct Patn Mode)..." + this.jobRunPol.getCondWhere());

			XLLogger.outputInfoLog("");
			
			// Oracle Connection 생성
			XLDBMSInfo sdbInfo = this.jobRunPol.getSdbInfo();
			
			
			// cksohn - xl bulk mode for oracle - XL_BULK_ORACLE_EOL start - [
			XLDBMSInfo tdbInfo = this.jobRunPol.getTdbInfo(); 
			byte tdbType = tdbInfo.getDbType();
			
			// ] - end 
			
			// gssg - xl m2m bulk mode 지원
			
			
			mariaDBConnObj = new XLMariaDBConnection(
					sdbInfo.getIp(), 
					sdbInfo.getDbSid(),
					sdbInfo.getUserId(),
					sdbInfo.getPasswd(),
					sdbInfo.getPort(),
					sdbInfo.getDbType() 
					);
			
			// Target DB Connection
			// gssg - xl m2m bulk mode 지원
			if ( !mariaDBConnObj.makeConnection() ) {
				
				errMsg = "[EXCEPTION] Failed to make source db connection - " + sdbInfo.getIp() + "/" + sdbInfo.getDbSid();
				XLLogger.outputInfoLog(errMsg);
				// TODO 여기서 Catalog DB에 실패로 update 치고 끝나야 하는데,, catalog 가 타겟에 있을 경우 문제가 되긴함. 
				//      그러면, 추후 수행시 깨끗하게 JOBQ를 지우고 수행하도록 조치해야 할 수도 있음. 
				
				this.jobRunPol.setStopJobFlag(true);
				XLLogger.outputInfoLog(this.logHead +  "[EXCEPTION] Recv Thread is stopped abnormal.");
				
				this.jobRunPol.setJobStatus(XLOGCons.STATUS_FAIL);
				this.jobRunPol.setErrMsg_Recv(errMsg);
				return;
			} else {
				XLLogger.outputInfoLog(this.logHead + " Source DBMS is connected - " +  sdbInfo.getIp() + "/" + sdbInfo.getDbSid());
			}
			// Target 반영 insert preparedStatement 구문 생성
			
			// gssg - xl m2m bulk mode 지원
			this.pstmtSelect = (PreparedStatement)mariaDBConnObj.getConnection().prepareStatement(this.jobRunPol.getSrcSelectSql());
			
			// gssg - 카카오 - m2m bulk mode 보완
			this.pstmtSelect.setFetchSize(XLConf.XL_FETCH_SIZE);
			stime = System.currentTimeMillis();
			
			rs = this.pstmtSelect.executeQuery();
			Vector<ArrayList<String>> vtData = new Vector<ArrayList<String>>();
			
			String value = null;
			
			// cksohn - BULK mode oracle sqlldr
			this.pipe = new RandomAccessFile( this.jobRunPol.getBulk_pipePath(), "rw" );
			
			// cksohn - BULK mode oracle sqlldr
			// TEST CODE!!!
			// FileWriter fw_test = new FileWriter("/tmp/ck.dat", false);
			
			
			long rowCnt = 0;
			
			long recvCnt = 0; // skip 한것을 제외한 select count 건수
			
			// cksohn - BULK mode oracle sqlldr
			StringBuffer sb_record = new StringBuffer(); // record Data
			
			while ( rs.next() ) {
			
				rowCnt++;
				// 이미 commit된 건수는 skip
				if ( rowCnt <= this.jobRunPol.getCondCommitCnt() ) {
					continue;
				}
				
				// ArrayList<String> arrayList = new ArrayList<>(); // record Data
				
				for (int i=0; i<this.vtColInfo.size(); i++) {
					
					XLJobColInfo colInfo = this.vtColInfo.get(i);
					switch ( colInfo.getDataType() ) { // gssg - xl m2m bulk mode 지원

					
					// gssg - xl m2m bulk mode 지원
					case XLDicInfoCons.YEAR:
						
						if (rs.getString(i+1) == null ) {
							// value = ""; // oracle은 "" 도 null
							value = "\\N";

						}else {
							SimpleDateFormat transFormat = new SimpleDateFormat("yyyy");					
							java.util.Date year = transFormat.parse(rs.getString(i+1));							
							value = transFormat.format(year);								
						}						

						break;

					// gssg - xl m2m bulk mode 지원
					 case XLDicInfoCons.CHAR:
					 case XLDicInfoCons.VARCHAR:					
						 
					 	value = rs.getString(i+1);				
					 	
						value = XLUtil.replaceCharToCSV_MYSQL(value);
						
						if (value == null ) {
							value = "\\N"; // gssg - xl m2m bulk mode 지원
						}
						
						break;
						
					 // gssg - xl o2m 지원
					 // " 값 처리
					 case XLDicInfoCons.TINYTEXT:
					 case XLDicInfoCons.TEXT:
					 case XLDicInfoCons.MEDIUMTEXT:
					 case XLDicInfoCons.LONGTEXT:
						 							
							value = XLUtil.replaceCharToCSV_MYSQL(rs.getString(i+1));
							
							if (value == null ) {
								value = "\\N"; // gssg - xl m2m bulk mode 지원
							}
							
							break;

					 // gssg - xl 전체적으로 보완
					 // gssg - m2m bulk mode binary 타입 처리
					 case XLDicInfoCons.BIT:
					 case XLDicInfoCons.BINARY:
					 case XLDicInfoCons.VARBINARY:
					 case XLDicInfoCons.TINYBLOB:
					 case XLDicInfoCons.BLOB:
					 case XLDicInfoCons.MEDIUMBLOB:
					 case XLDicInfoCons.LONGBLOB:
							byte[] bAry = rs.getBytes(i+1);						
							if ( bAry == null ) {
								value = "\\N";
							} else {
								value = XLUtil.bytesToHexString(bAry);
							}				
							break;


						
					default : 						
						value = rs.getString(i+1);
						if (value == null ) {
							// value = ""; // oracle은 "" 도 null
							value = "\\N"; // gssg - xl m2m bulk mode 지원
						}
						break;
						
					}	
					// arrayList.add(value);
					// cksohn - BULK mode oracle sqlldr
					if ( i != 0 ) {
						sb_record.append(",");
						
					}
					// gssg - xl m2m bulk mode 지원
					sb_record.append("\"").append(value).append("\"");
					
				} // for-end 1개의 record 추출 종료
				
				// gssg - xl m2m bulk mode 지원
				if ( tdbType == XLCons.ORACLE && !XLConf.XL_BULK_ORACLE_EOL.equals("")) {
					
					sb_record.append(XLConf.XL_BULK_ORACLE_EOL);
				} else {
					// cksohn - xl bulk mode for oracle - 결과 처리 오류 수정
					// 타겟이 오라클일경우는 \n write 안함
					sb_record.append("\n");
				}
				
				
				// XLLogger.outputInfoLog("CKSOHN DEBUG sb_record = " + sb_record.toString());
								
				recvCnt++;				
				
				
				
				// cksohn - BULK mode oracle sqlldr
				// if ( (recvCnt % XLConf.XL_MGR_SEND_COUNT ) == 0) {
				// cksohn - xl bulk mode 성능 개선 - t2o
				if ( (recvCnt % XLConf.XL_BATCH_SIZE ) == 0) {
					
					// pipe.writeBytes(sb_record.toString());
					// cksohn - xl bulk mode for oracle - special character loading error
					pipe.write(sb_record.toString().getBytes("UTF-8"));
										
					// XLLogger.outputInfoLog(this.logHead + "Recv DI Send data size = " +  sb_record.length());

					sb_record = new StringBuffer(); // 초기화
					
				}
				
							
			} // while-end
			
			// remain data 처리 
			if ( sb_record.toString().length() > 0 ) {
				// 나머지 데이터 존재 
				// pipe.writeBytes(sb_record.toString());
				// cksohn - xl bulk mode for oracle - special character loading error
				pipe.write(sb_record.toString().getBytes("UTF-8"));
				
				// XLLogger.outputInfoLog(this.logHead + "Recv DI Send data size = " +  sb_record.length());
			}
			
			
			etime = System.currentTimeMillis();
			
			long elapsedTime = (etime-stime) / 1000;
			
			//XLLogger.outputInfoLog("");
			XLLogger.outputInfoLog(this.logHead + " Completed Job Recv : " + this.jobRunPol.getCondWhere());
			XLLogger.outputInfoLog("\tTotal Select count : " + recvCnt + " / Elapsed time(sec) : " +  elapsedTime);
			//XLLogger.outputInfoLog("");
			
			// gssg - xl m2m bulk mode logging 지원
			this.jobRunPol.setApplyCnt(recvCnt); // gssg - xl recvCnt를 applyCnt로 적용
			
			
			// cksohn - BULK mode oracle sqlldr
			this.dataQ.notifyEvent();
			
			return;
			
		} catch (Exception e) {
			
			XLException.outputExceptionLog(e);
			errMsg = e.toString();
			this.jobRunPol.setJobStatus(XLOGCons.STATUS_FAIL);
			this.jobRunPol.setErrMsg_Recv(errMsg);
			
		} finally {
			
			// cksohn - BULK mode oracle sqlldr
			try { if (pipe != null) pipe.close(); } catch (Exception e1) {} finally { pipe = null; }
			
			try { if ( this.pstmtSelect != null ) this.pstmtSelect.close(); } catch (Exception e) {} finally { this.pstmtSelect = null; }
			try { if ( rs != null ) rs.close(); } catch (Exception e) {} finally { rs = null; }
			
			
			// gssg - xl m2m bulk mode 지원
			// gssg - xl 전체적으로 보완
		    // gssg - m2m bulk mode thread 순서 조정
			// gssg - p2p 하다가 m2m bulk mode 스레드 순서 조정
			// gssg - 카카오 - m2m bulk mode 보완
//			int chkCnt = 0;
//
//			if ( XLConf.XL_MGR_DEBUG_YN ) {
//				XLLogger.outputInfoLog("[DEBUG] ----- START isLoadQuery WHILE!!!!! - " + this.jobRunPol.isLoadQuery());
//			}
//
//				try {
//					while ( !this.jobRunPol.isLoadQuery() && chkCnt <= 10 ) {
//
//					chkCnt++;
//					XLLogger.outputInfoLog("[" + this.jobRunPol.getPolName() + "][RECV BULK] Waiting Check Loader State.(" + chkCnt + ")");					
//					Thread.sleep(1000);
//					}
//					
//				} catch (InterruptedException e) {
//					// TODO Auto-generated catch block
//					e.printStackTrace();
//				}
//			if ( XLConf.XL_MGR_DEBUG_YN ) {
//				XLLogger.outputInfoLog("[DEBUG] ----- END isLoadQuery WHILE!!!! - " + this.jobRunPol.isLoadQuery());
//			}
				
//			try { if ( this.mariaDBConnObj != null && this.jobRunPol.isLoadQuery() ) this.mariaDBConnObj.closeConnection(); } catch (Exception e) {} finally { this.mariaDBConnObj = null; }							

			try { if ( this.mariaDBConnObj != null ) this.mariaDBConnObj.closeConnection(); } catch (Exception e) {} finally { this.mariaDBConnObj = null; }
			
			// cksohn - BULK mode oracle sqlldr
			this.dataQ.notifyEvent();
		}
		
	}
}
