package com.andreyceilings.shared.db

import app.cash.sqldelight.db.*
import kotlinx.coroutines.flow.MutableStateFlow

/** Real outer rollback and lost response injection; never substitutes the SQL writer. */
class DetailsExitFaultDriver(private val raw:SqlDriver):SqlDriver by raw {
    enum class Fault { NONE, ROLLBACK, LOST_RESPONSE }
    private val fault=MutableStateFlow(Fault.NONE)
    var afterInsert:(()->Unit)?=null
    fun failNext(value:Fault) {fault.value=value}
    override fun execute(identifier:Int?,sql:String,parameters:Int,binders:(SqlPreparedStatement.()->Unit)?):QueryResult<Long> {
        val result=raw.execute(identifier,sql,parameters,binders)
        if(sql.trimStart().startsWith("INSERT INTO ceiling_operations")) {
            afterInsert?.invoke()
            if(fault.compareAndSet(Fault.ROLLBACK,Fault.NONE))error("Injected after journal insert before outer commit")
        }
        return result
    }
    override fun notifyListeners(vararg queryKeys:String) {
        raw.notifyListeners(*queryKeys)
        if("ceiling_operations" in queryKeys && fault.compareAndSet(Fault.LOST_RESPONSE,Fault.NONE))error("Injected lost commit response")
    }
}
