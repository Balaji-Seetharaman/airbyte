#
# Copyright (c) 2021 Airbyte, Inc., all rights reserved.
#
from airbyte_cdk.sources.cac.states.dict_state import DictState

vars = {}
config = {"name": "date"}
name = "{{ config['name'] }}"
value = "{{ last_record['updated_at'] }}"


def test_empty_state_is_none():
    state = DictState(name, value, str, vars, config)
    initial_state = state.get_state()
    # FIXME: Is this the right init or should it be NONE or an empty dict?
    expected_state = {"date": None}
    assert expected_state == initial_state


def test_update_initial_state():
    state = DictState(name, value, str, vars, config)
    stream_slice = None
    stream_state = None
    last_response = {"data": {"id": "1234", "updated_at": "2021-01-01"}, "last_refresh": "2020-01-01"}
    last_record = {"id": "1234", "updated_at": "2021-01-01"}
    state.update_state(stream_slice, stream_state, last_response, last_record)
    actual_state = state.get_state()
    expected_state = {"date": "2021-01-01"}
    assert expected_state == actual_state


def test_update_state_with_recent_cursor():
    state = DictState(name, value, str, vars, config)
    stream_slice = None
    stream_state = {"date": "2020-12-31"}
    last_response = {"data": {"id": "1234", "updated_at": "2021-01-01"}, "last_refresh": "2020-01-01"}
    last_record = {"id": "1234", "updated_at": "2021-01-01"}
    state.update_state(stream_slice, stream_state, last_response, last_record)
    actual_state = state.get_state()
    expected_state = {"date": "2021-01-01"}
    assert expected_state == actual_state


def test_update_state_with_old_cursor():
    state = DictState(name, value, str, vars, config)
    stream_slice = None
    stream_state = {"date": "2021-01-02"}
    last_response = {"data": {"id": "1234", "updated_at": "2021-01-01"}, "last_refresh": "2020-01-01"}
    last_record = {"id": "1234", "updated_at": "2021-01-01"}
    state.update_state(stream_slice, stream_state, last_response, last_record)
    actual_state = state.get_state()
    expected_state = {"date": "2021-01-02"}
    assert expected_state == actual_state


def test_update_state_with_older_state():
    state = DictState(name, value, str, vars, config)
    stream_slice = None
    stream_state = {"date": "2021-01-02"}
    last_response = {"data": {"id": "1234", "updated_at": "2021-01-02"}, "last_refresh": "2020-01-01"}
    last_record = {"id": "1234", "updated_at": "2021-01-02"}
    state.update_state(stream_slice, stream_state, last_response, last_record)
    actual_state = state.get_state()
    expected_state = {"date": "2021-01-02"}

    out_of_order_response = {"data": {"id": "1234", "updated_at": "2021-01-02"}, "last_refresh": "2020-01-01"}
    out_of_order_record = {"id": "1234", "updated_at": "2021-01-01"}
    state.update_state(stream_slice, stream_state, out_of_order_response, out_of_order_record)
    assert expected_state == actual_state


def test_state_is_a_timestamp():
    state = DictState(name, value, int, vars, config)
    stream_slice = None
    stream_state = {"date": 12345}
    last_response = {"data": {"id": "1234", "updated_at": 123456}, "last_refresh": "2020-01-01"}
    last_record = {"id": "1234", "updated_at": 123456}
    state.update_state(stream_slice, stream_state, last_response, last_record)
    actual_state = state.get_state()
    expected_state = {"date": 123456}
    assert expected_state == actual_state
