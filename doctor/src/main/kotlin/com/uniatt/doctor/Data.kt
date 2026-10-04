package com.uniatt.doctor

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** المواد تأتي من ملف الكشف فقط (معرّفها هو معرّف المسؤول). */
@Entity data class CourseSection(
    @PrimaryKey val id: Long,
    val code: String, val name: String, val section: String
)

/**
 * tag/keyHex: من كود الطالب (الوسم للبحث، والمفتاح للتحقق من معرفته بالكود).
 * publicKeyHex: مفتاح جهازه، يُربط تلقائيًا عند أول تقريب ناجح (null = لم يرتبط بعد).
 */
@Entity(indices = [Index(value = ["tag"], unique = true)])
data class StudentEntity(
    @PrimaryKey val studentId: String,
    val name: String, val faculty: String, val major: String, val level: String, val section: String,
    val tag: String, val keyHex: String,
    val publicKeyHex: String? = null
)

@Entity(primaryKeys = ["studentId", "courseSectionId"])
data class Enrollment(val studentId: String, val courseSectionId: Long)

@Entity data class SessionEntity(
    @PrimaryKey val sessionId: String,
    val courseSectionId: Long, val doctorId: String,
    val startedAt: Long, val durationMin: Int, val endedAt: Long? = null
)

/** القيد الفريد (sessionId, studentId) يمنع التسجيل المكرر على مستوى قاعدة البيانات. */
@Entity(indices = [Index(value = ["sessionId", "studentId"], unique = true)])
data class AttendanceRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String, val studentId: String, val studentName: String,
    val courseSectionId: Long, val courseName: String,
    val date: String, val timestamp: Long, val status: String
)

@Dao interface AttDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertCourse(c: CourseSection)
    @Query("DELETE FROM Enrollment WHERE courseSectionId IN (:ids)") suspend fun clearEnrollments(ids: List<Long>)
    @Query("SELECT * FROM CourseSection ORDER BY name") fun courses(): Flow<List<CourseSection>>
    @Query("SELECT * FROM CourseSection") suspend fun coursesOnce(): List<CourseSection>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertStudent(s: StudentEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun enroll(e: Enrollment)
    @Query("SELECT * FROM StudentEntity WHERE studentId=:id") suspend fun student(id: String): StudentEntity?
    @Query("SELECT * FROM StudentEntity WHERE tag=:tag") suspend fun studentByTag(tag: String): StudentEntity?
    @Query("UPDATE StudentEntity SET publicKeyHex=:k WHERE studentId=:id") suspend fun bindKey(id: String, k: String)
    @Query("SELECT * FROM StudentEntity WHERE publicKeyHex IS NOT NULL") suspend fun boundStudents(): List<StudentEntity>

    @Query("SELECT COUNT(*) FROM Enrollment WHERE studentId=:sid AND courseSectionId=:cid")
    suspend fun enrolled(sid: String, cid: Long): Int
    @Query("SELECT c.* FROM CourseSection c JOIN Enrollment e ON e.courseSectionId=c.id WHERE e.studentId=:sid")
    suspend fun coursesOfStudent(sid: String): List<CourseSection>
    @Query("SELECT s.* FROM StudentEntity s JOIN Enrollment e ON e.studentId=s.studentId WHERE e.courseSectionId=:cid ORDER BY s.name")
    fun studentsOf(cid: Long): Flow<List<StudentEntity>>
    @Query("SELECT s.* FROM StudentEntity s JOIN Enrollment e ON e.studentId=s.studentId WHERE e.courseSectionId=:cid ORDER BY s.name")
    suspend fun studentsOfOnce(cid: Long): List<StudentEntity>

    @Insert suspend fun addSession(s: SessionEntity)
    @Query("UPDATE SessionEntity SET endedAt=:t WHERE sessionId=:id") suspend fun endSession(id: String, t: Long)
    @Query("SELECT * FROM SessionEntity WHERE courseSectionId=:cid ORDER BY startedAt DESC")
    fun sessionsOf(cid: Long): Flow<List<SessionEntity>>
    @Query("SELECT * FROM SessionEntity") suspend fun allSessions(): List<SessionEntity>
    @Query("SELECT COUNT(*) FROM SessionEntity WHERE courseSectionId=:cid") suspend fun sessionCount(cid: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addAttendance(a: AttendanceRecord): Long
    @Query("SELECT * FROM AttendanceRecord WHERE sessionId=:sid ORDER BY timestamp DESC")
    fun attendanceOf(sid: String): Flow<List<AttendanceRecord>>
    @Query("SELECT * FROM AttendanceRecord WHERE courseSectionId=:cid") suspend fun attendanceForCourse(cid: Long): List<AttendanceRecord>
    @Query("SELECT * FROM AttendanceRecord") suspend fun allAttendance(): List<AttendanceRecord>
}

@Database(
    entities = [CourseSection::class, StudentEntity::class, Enrollment::class, SessionEntity::class, AttendanceRecord::class],
    version = 2, exportSchema = false
)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): AttDao
    companion object {
        @Volatile private var I: AppDb? = null
        fun get(c: Context): AppDb = I ?: synchronized(this) {
            I ?: Room.databaseBuilder(c.applicationContext, AppDb::class.java, "attendance.db")
                .fallbackToDestructiveMigration()       // نسخ التطوير القديمة فقط؛ لا بيانات إنتاج قبل الإصدار 2
                .build().also { I = it }
        }
    }
}
